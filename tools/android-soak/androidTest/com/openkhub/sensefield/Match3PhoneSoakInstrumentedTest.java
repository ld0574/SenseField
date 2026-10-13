package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;

/** Opt-in endurance workload; separately authorized physical and emulator scopes. */
public final class Match3PhoneSoakInstrumentedTest {
    private static void save(File directory,String name,JSONObject value) throws Exception {
        Files.write(new File(directory,name+".part").toPath(),value.toString(2).getBytes(StandardCharsets.UTF_8));
        assertTrue(new File(directory,name+".part").renameTo(new File(directory,name)));
    }
    private static JSONObject device(Context context,long elapsed) throws Exception {
        Intent battery=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        PowerManager power=context.getSystemService(PowerManager.class);
        Debug.MemoryInfo memory=new Debug.MemoryInfo();Debug.getMemoryInfo(memory);
        Runtime runtime=Runtime.getRuntime();
        return new JSONObject().put("elapsed_ms",elapsed).put("battery_tenths_c",battery==null?-1:battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,-1))
            .put("battery_percent",battery==null?-1:battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1))
            .put("plugged",battery==null?-1:battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,-1))
            .put("thermal_status",power==null?-1:power.getCurrentThermalStatus()).put("screen_interactive",power!=null&&power.isInteractive())
            .put("total_pss_kib",memory.getTotalPss()).put("native_heap_bytes",Debug.getNativeHeapAllocatedSize()).put("process_cpu_ms",android.os.Process.getElapsedCpuTime())
            .put("java_used_bytes",runtime.totalMemory()-runtime.freeMemory()).put("java_committed_bytes",runtime.totalMemory());
    }
    private static JSONObject timing(List<Long> values) throws Exception {
        List<Long> sorted=new ArrayList<>(values);Collections.sort(sorted);JSONObject result=new JSONObject().put("n",sorted.size());
        if(sorted.isEmpty())return result;
        return result.put("min_ms",sorted.get(0)/1e6).put("median_ms",sorted.get(sorted.size()/2)/1e6)
            .put("p95_nearest_rank_ms",sorted.get((int)Math.ceil(sorted.size()*.95)-1)/1e6).put("max_ms",sorted.get(sorted.size()-1)/1e6);
    }
    @Test public void sustainedFixedFramesWithProductionDiagnostics() throws Exception {
        Bundle args=InstrumentationRegistry.getArguments();
        boolean emulator=Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish");
        assertEquals("true",args.getString(emulator?"authorizedEmulatorAcceptance":"authorizedPhoneAcceptance"));
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Map<String,?> preferences=new HashMap<>(GameProfile.settings(app).getAll());
        assertNull("no active assistance diagnostics",DiagnosticRecorder.current);
        File realDiagnostics=DiagnosticRecorder.root(app);
        Set<String> originalSessions=new TreeSet<>();File[] before=realDiagnostics.listFiles();if(before!=null)for(File f:before)originalSessions.add(f.getName());
        File inputs=app.getExternalFilesDir("match3-soak-inputs");
        byte[] manifestBytes=Files.readAllBytes(new File(inputs,"manifest.json").toPath());
        JSONObject manifest=new JSONObject(new String(manifestBytes,StandardCharsets.UTF_8));assertFalse(manifest.getBoolean("independent"));
        JSONArray entries=manifest.getJSONArray("frames");assertEquals(19,entries.length());
        File out=new File(app.getExternalFilesDir("match3-soak"),args.getString("soakRun"));assertTrue(out.mkdirs());
        File files=new File(out,"runtime-files");assertTrue(files.mkdirs());
        Context isolated=new ContextWrapper(app) {
            @Override public Context getApplicationContext(){return this;}
            @Override public File getFilesDir(){return files;}
        }; // Production archive retention applies only within this new scratch root.
        int seconds=Integer.parseInt(args.getString("soakSeconds","900"));assertTrue(seconds>=10&&seconds<=900);
        long duration=seconds*1000L,start=SystemClock.elapsedRealtime(),nextTick=start,nextTelemetry=start;
        JSONObject report=new JSONObject().put("scope",(emulator?"emulator":"physical_phone")+"_fixed_replay_at_800ms; includes_geometry_recognition_ranker_and_production_async_diagnostics; excludes_projection_overlay_tts_and_controlled_thermal_comparison")
            .put("device_kind",emulator?"emulator":"physical")
            .put("source_sha256",args.getString("match3SourceSha256")).put("harness_sha256",args.getString("phoneHarnessSha256"))
            .put("manifest_sha256",Match3HoldoutAccuracyInstrumentedTest.sha256(manifestBytes)).put("requested_duration_ms",duration)
            .put("processing_interval_ms",800).put("source_frame_count",entries.length()).put("workload_long_edge",2712)
            .put("resize_scope","memory_and_processing_load_only; no_accuracy_claim").put("independent",false).put("controlled_thermal_acceptance",false)
            .put("workload_note","auto geometry each observation; rank confirmed cells each observation; intentionally excludes live service rank caching and goal confirmation")
            .put("model",Build.MODEL).put("sdk",Build.VERSION.SDK_INT);
        Match3SoakWakeClient wake=null;DiagnosticRecorder recorder=null;Match3Sampler sampler=null;Match3HudReader hud=null;
        Bitmap frame=null;ByteBuffer rgba=null;int loaded=-1,observations=0,ranked=0,missingGeometry=0,lateTicks=0,maxThermal=-1;
        Match3UnknownElements unknown=new Match3UnknownElements();Match3FlywheelHarvest harvest=new Match3FlywheelHarvest();
        Match3CellConfirmation confirmation=new Match3CellConfirmation();BoardGeometry grid=null;
        List<Long> costs=new ArrayList<>(),cpus=new ArrayList<>(),ioWaits=new ArrayList<>();JSONArray telemetry=new JSONArray();JSONArray frameVisits=new JSONArray();
        String reason="completed";Throwable primaryError=null;JSONArray cleanupErrors=new JSONArray();long workloadElapsed=0;AtomicInteger acceptedHarvest=new AtomicInteger();int over800=0;
        int[] sourceObservations=new int[entries.length()],sourceRanked=new int[entries.length()],sourceMissing=new int[entries.length()];
        try {
            wake=new Match3SoakWakeClient(app,InstrumentationRegistry.getInstrumentation().getContext().getPackageName(),duration+60000);
            report.put("wake_scope","bound_test_apk_process_owns_normal_permission; no_ui_automation_or_shell_identity")
                .put("wake_helper_uid",wake.helperUid);
            String diagnosticSession=UUID.randomUUID().toString();report.put("diagnostic_session_id",diagnosticSession);
            recorder=DiagnosticRecorder.start(isolated,diagnosticSession,start,DiagnosticGame.MATCH3);recorder.setImagesEnabled(true);
            hud=new Match3HudReader(app);
            while(SystemClock.elapsedRealtime()-start<duration) {
                long now=SystemClock.elapsedRealtime();if(now<nextTick){SystemClock.sleep(nextTick-now);continue;}
                int index=(observations/8)%entries.length();
                if(index!=loaded) {
                    JSONObject entry=entries.getJSONObject(index);File input=new File(inputs,entry.getString("file"));assertTrue(input.getCanonicalPath().startsWith(inputs.getCanonicalPath()+File.separator));
                    byte[] bytes=Files.readAllBytes(input.toPath());assertEquals(entry.getString("sha256"),Match3HoldoutAccuracyInstrumentedTest.sha256(bytes));
                    Bitmap decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.length);assertNotNull(decoded);
                    if(frame!=null)frame.recycle();float scale=2712f/Math.max(decoded.getWidth(),decoded.getHeight());
                    frame=Bitmap.createScaledBitmap(decoded,Math.round(decoded.getWidth()*scale),Math.round(decoded.getHeight()*scale),true);if(frame!=decoded)decoded.recycle();
                    int size=frame.getByteCount();if(rgba==null||rgba.capacity()!=size)rgba=ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
                    loaded=index;confirmation=new Match3CellConfirmation();hud.clear();harvest.resetStability();unknown.resetStability();
                    frameVisits.put(new JSONObject().put("source_index",index).put("file",entry.getString("file")).put("elapsed_ms",SystemClock.elapsedRealtime()-start));
                }
                long at=SystemClock.elapsedRealtime(),cpuAt=Debug.threadCpuTimeNanos();BoardGeometry candidate=Match3Sampler.autoDetectGeometry(frame);
                if(candidate==null){missingGeometry++;sourceMissing[index]++;}
                else {
                    if(sampler==null||!candidate.sameGrid(grid)){if(sampler!=null)sampler.close();grid=candidate;sampler=new Match3Sampler(app,grid);}
                    Match3Position position=sampler.samplePosition(frame,at);Match3CellConfirmation.Snapshot confirmed=confirmation.accept(position,at);Match3Goals goals=hud.read(frame,grid,at);
                    if(confirmed!=null){Match3MoveRanker.rankedMoves(confirmed.position,goals);ranked++;sourceRanked[index]++;}
                    for(Match3UnknownElements.Sample sample:unknown.observe(sampler.reviewElements(position),at))recorder.elementSample(frame,grid,sample);
                    final DiagnosticRecorder activeRecorder=recorder;
                    harvest.collect(sampler,position,hud,at,data->{boolean accepted=activeRecorder.flywheelSample(data);if(accepted)acceptedHarvest.incrementAndGet();return accepted;});
                }
                rgba.rewind();frame.copyPixelsToBuffer(rgba);rgba.rewind();
                recorder.frame(NativeFrameResult.empty(),DiagnosticSnapshot.parse(null),rgba,frame.getWidth(),frame.getHeight(),frame.getRowBytes(),at,SystemClock.elapsedRealtime(),800);
                long cost=(SystemClock.elapsedRealtime()-at)*1000000L;costs.add(cost);cpus.add(Debug.threadCpuTimeNanos()-cpuAt);observations++;sourceObservations[index]++;if(cost>800000000L)over800++;
                if(now>=nextTelemetry) {
                    JSONObject sample=device(app,SystemClock.elapsedRealtime()-start);telemetry.put(sample);maxThermal=Math.max(maxThermal,sample.getInt("thermal_status"));
                    long ioAt=SystemClock.elapsedRealtimeNanos();DiagnosticRecorder.IO.submit(()->{}).get(10,TimeUnit.SECONDS);ioWaits.add(SystemClock.elapsedRealtimeNanos()-ioAt);
                    assertTrue("helper CPU wake remains held",wake.isHeld());
                    save(out,"progress.json",new JSONObject().put("observations",observations).put("ranked_frames",ranked).put("missing_geometry",missingGeometry).put("sample",sample));
                    nextTelemetry=now+10000;
                    if(sample.getInt("battery_tenths_c")>=430||sample.getInt("thermal_status")>=PowerManager.THERMAL_STATUS_SEVERE){reason="thermal_stop";break;}
                }
                if(!recorder.failure.isEmpty())throw new IllegalStateException(recorder.failure);
                nextTick+=800;if(SystemClock.elapsedRealtime()>nextTick){lateTicks++;nextTick=SystemClock.elapsedRealtime()+800;}
            }
        } catch(Throwable error) {reason=error.getClass().getSimpleName()+":"+error.getMessage();primaryError=error;}
        finally {
            workloadElapsed=SystemClock.elapsedRealtime()-start;
            try {if(recorder!=null){recorder.finish(reason);DiagnosticRecorder.IO.submit(()->{}).get(15,TimeUnit.SECONDS);report.put("diagnostic_directory",recorder.directory.getAbsolutePath()).put("diagnostic_error",recorder.failure).put("image_work_stats",new JSONArray(recorder.imageWorkStats()));}}
            catch(Throwable e){cleanupErrors.put("diagnostic_finish:"+e);}
            finally {
                if(DiagnosticRecorder.current==recorder)DiagnosticRecorder.current=null;
                try{if(sampler!=null)sampler.close();}catch(Throwable e){cleanupErrors.put("sampler_close:"+e);}
                try{if(hud!=null)hud.close();}catch(Throwable e){cleanupErrors.put("hud_close:"+e);}
                try{if(frame!=null)frame.recycle();}catch(Throwable e){cleanupErrors.put("bitmap_recycle:"+e);}
                try{if(wake!=null)wake.close();}catch(Throwable e){cleanupErrors.put("wakelock_release:"+e);}
            }
            Set<String> afterSessions=new TreeSet<>();File[] after=realDiagnostics.listFiles();if(after!=null)for(File f:after)afterSessions.add(f.getName());
            JSONArray sourceStats=new JSONArray();boolean allSourcesExercised=true;
            for(int i=0;i<entries.length();i++) {sourceStats.put(new JSONObject().put("source_index",i).put("file",entries.getJSONObject(i).getString("file"))
                .put("observations",sourceObservations[i]).put("ranked",sourceRanked[i]).put("missing_geometry",sourceMissing[i]));allSourcesExercised&=sourceRanked[i]>0;}
            report.put("elapsed_ms",SystemClock.elapsedRealtime()-start).put("observations",observations).put("ranked_frames",ranked).put("missing_geometry",missingGeometry)
                .put("workload_elapsed_ms",workloadElapsed).put("late_ticks",lateTicks).put("over_800ms",over800).put("reason",reason)
                .put("wall_timing",timing(costs)).put("cpu_timing",timing(cpus)).put("io_drain_timing",timing(ioWaits))
                .put("frame_visits",frameVisits).put("source_stats",sourceStats).put("all_sources_exercised",allSourcesExercised).put("accepted_flywheel_samples",acceptedHarvest.get()).put("cleanup_errors",cleanupErrors)
                .put("max_thermal_status",maxThermal).put("telemetry",telemetry).put("processing_wall_ns",new JSONArray(costs)).put("processing_cpu_ns",new JSONArray(cpus))
                .put("original_session_names_unchanged",originalSessions.equals(afterSessions)).put("preferences_unchanged",preferences.equals(GameProfile.settings(app).getAll()))
                .put("passed","completed".equals(reason)&&cleanupErrors.length()==0&&observations>0&&ranked>0&&workloadElapsed>=duration&&(seconds<900||allSourcesExercised));
            save(out,"report.json",report);
        }
        if(primaryError!=null)throw new AssertionError("workload failed",primaryError);
        assertEquals("completed",reason);assertTrue(report.getBoolean("passed"));assertTrue(report.getBoolean("preferences_unchanged"));
        assertTrue(report.getBoolean("original_session_names_unchanged"));
    }
}
