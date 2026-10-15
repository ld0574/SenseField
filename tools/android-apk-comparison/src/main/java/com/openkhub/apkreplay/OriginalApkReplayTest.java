package com.openkhub.apkreplay;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Calls only retained code in the installed original APK. No application code is copied here. */
public final class OriginalApkReplayTest {
    private static final String P="com.openkhub.sensefield.";
    private static String hash(byte[] data) throws Exception {
        byte[] h=MessageDigest.getInstance("SHA-256").digest(data);StringBuilder out=new StringBuilder();
        for(byte b:h)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private static String hash(File file) throws Exception {
        MessageDigest h=MessageDigest.getInstance("SHA-256");byte[] buf=new byte[65536];
        try(InputStream in=Files.newInputStream(file.toPath())) {int n;while((n=in.read(buf))!=-1)h.update(buf,0,n);}
        StringBuilder out=new StringBuilder();for(byte b:h.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private static Object jsonValue(Object value) {
        if(value==null)return JSONObject.NULL;
        if(value instanceof Enum<?>)return ((Enum<?>)value).name();
        if(value instanceof Character)return (char)value=='\0'?"":String.valueOf(value);
        return value;
    }

    // Contract resolution, class initialization and hashing happen before timing.
    private static final class Api {
        final Map<String,Field> fields=new HashMap<>();
        final Map<String,Method> methods=new HashMap<>();
        final Map<String,Constructor<?>> constructors=new HashMap<>();
        final JSONObject contract;final String apkSha,contractSha;
        final ClassLoader loader;
        Api(Context app,Context producer) throws Exception {
            apkSha=hash(new File(app.getApplicationInfo().sourceDir));loader=app.getClassLoader();
            byte[] bytes=null;
            for(String name:producer.getAssets().list("contracts")) {
                try(InputStream in=producer.getAssets().open("contracts/"+name)) {
                    byte[] data=in.readAllBytes();JSONObject c=new JSONObject(new String(data,StandardCharsets.UTF_8));
                    if(c.getString("apk_sha256").equals(apkSha)){assertNull("unique APK contract",bytes);bytes=data;}
                }
            }
            assertNotNull("exact installed APK contract",bytes);contractSha=hash(bytes);
            contract=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            JSONObject classes=contract.getJSONObject("classes");
            for(java.util.Iterator<String> keys=classes.keys();keys.hasNext();) {
                String original=keys.next();JSONObject entry=classes.getJSONObject(original);
                Class<?> cl=Class.forName(entry.getString("name"),true,loader);
                JSONObject fs=entry.getJSONObject("fields"),ms=entry.getJSONObject("methods");
                for(java.util.Iterator<String> k=fs.keys();k.hasNext();) {
                    String f=k.next();Field field=cl.getDeclaredField(fs.getJSONObject(f).getString("name"));
                    field.setAccessible(true);fields.put(original+"."+f,field);
                }
                for(java.util.Iterator<String> k=ms.keys();k.hasNext();) {
                    String m=k.next();JSONObject meta=ms.getJSONObject(m);String alias=meta.getString("name");
                    if(alias.equals("<clinit>"))continue;
                    JSONArray names=meta.getJSONArray("parameters");Class<?>[] args=new Class<?>[names.length()];
                    for(int i=0;i<args.length;i++)args[i]=type(names.getString(i));
                    if(alias.equals("<init>")) {Constructor<?> c=cl.getDeclaredConstructor(args);c.setAccessible(true);constructors.put(original+"."+m,c);}
                    else {Method method=cl.getDeclaredMethod(alias,args);method.setAccessible(true);methods.put(original+"."+m,method);}
                }
            }
        }
        Class<?> type(String name) throws Exception {
            if(name.endsWith("[]"))return Array.newInstance(type(name.substring(0,name.length()-2)),0).getClass();
            switch(name) {
                case "boolean":return boolean.class;case "byte":return byte.class;case "char":return char.class;
                case "short":return short.class;case "int":return int.class;case "long":return long.class;
                case "float":return float.class;case "double":return double.class;case "void":return void.class;
                default:return Class.forName(name,false,loader);
            }
        }
        Object call(String key,Object receiver,Object... args) throws Exception {
            Method method=methods.get(key);assertNotNull("retained production API: "+key,method);return method.invoke(receiver,args);
        }
        Object make(String key,Object... args) throws Exception {
            Constructor<?> c=constructors.get(key);assertNotNull("retained constructor: "+key,c);return c.newInstance(args);
        }
        Object get(String cl,String name,Object value) throws Exception {
            Field f=fields.get(cl+"."+name);assertNotNull("retained field: "+cl+"."+name,f);return f.get(value);
        }
        int number(String cl,String name,Object value) throws Exception{return ((Number)get(cl,name,value)).intValue();}
    }

    private static final class Step {
        Object geometry,board,goals;
        List<?> moves=Collections.emptyList();String reset="",status="no_geometry",hud="not_read";boolean motion;
    }
    private static final class Pipeline implements AutoCloseable {
        final Api api;final Context app;final Object hud;Object grid,sampler;
        Pipeline(Api api,Context app) throws Exception {this.api=api;this.app=app;hud=api.make("Match3HudReader.<init>(android.content.Context)",app);}
        void reset() throws Exception {
            if(sampler!=null)api.call("Match3Sampler.close()",sampler);
            sampler=null;grid=null;api.call("Match3HudReader.clear()",hud);
        }
        Step process(Bitmap image,long at,boolean reset,long[] stage) throws Exception {
            Step s=new Step();long start=Debug.threadCpuTimeNanos();
            if(reset){reset();s.reset="window_start";}
            Object next=api.call("Match3Sampler.autoDetectGeometry(android.graphics.Bitmap)",null,image);
            if(grid!=null && !(boolean)api.call("BoardGeometry.sameGrid("+P+"BoardGeometry)",grid,next)) {reset();s.reset="geometry_changed_or_unavailable";}
            if(sampler==null && next!=null){grid=next;sampler=api.make("Match3Sampler.<init>(android.content.Context,"+P+"BoardGeometry)",app,grid);}
            s.geometry=grid;long afterGeometry=Debug.threadCpuTimeNanos();stage[0]=afterGeometry-start;
            if(sampler==null)return s;
            s.goals=api.call("Match3HudReader.read(android.graphics.Bitmap,"+P+"BoardGeometry,long)",hud,image,grid,at);
            s.hud=(String)api.call("Match3HudReader.status()",hud);long afterHud=Debug.threadCpuTimeNanos();stage[1]=afterHud-afterGeometry;
            s.board=api.call("Match3Sampler.samplePosition(android.graphics.Bitmap,long)",sampler,image,at);
            s.motion=(boolean)api.get("Match3Sampler","frameMotion",sampler);
            long afterSample=Debug.threadCpuTimeNanos();stage[2]=afterSample-afterHud;
            s.moves=(List<?>)api.call("Match3MoveRanker.rankedMoves("+P+"Match3Position,"+P+"Match3Goals)",null,s.board,s.goals);
            stage[3]=Debug.threadCpuTimeNanos()-afterSample;s.status="observed_and_ranked";return s;
        }
        public void close() throws Exception {reset();api.call("Match3HudReader.close()",hud);}
    }
    private static JSONObject geometry(Api a,Object g) throws Exception {
        JSONObject o=new JSONObject();if(g==null)return o;
        for(String f:new String[]{"frameWidth","frameHeight","left","top","right","bottom","rows","cols"})o.put(f,a.get("BoardGeometry",f,g));
        return o.put("cellW",a.call("BoardGeometry.cellWidth()",g)).put("cellH",a.call("BoardGeometry.cellHeight()",g));
    }
    private static JSONObject cell(Api a,Object c,int r,int col) throws Exception {
        JSONObject o=new JSONObject().put("row",r).put("col",col);
        for(String f:new String[]{"kind","color","swappable","swapPermission","matchPermission","activationPermission","baseSwapPermission",
                "adjacentDamagePermission","specialDamagePermission","timedGate","layers","iceLayers","objectId","coverLayers","charge",
                "capacity","direction","absentTurns","cover","effect","gate","ruleId","coverRuleId","floorRuleId","ruleVersion"})
            o.put(f,jsonValue(a.get("Match3Position$Cell",f,c)));
        return o;
    }
    private static JSONObject position(Api a,Object board) throws Exception {
        JSONObject o=new JSONObject();if(board==null)return o;
        int rows=a.number("Match3Position","rows",board),cols=a.number("Match3Position","cols",board);JSONArray cells=new JSONArray();
        for(int r=0;r<rows;r++)for(int c=0;c<cols;c++)cells.put(cell(a,a.call("Match3Position.cell(int,int)",board,r,c),r,c));
        return o.put("rows",rows).put("cols",cols).put("cells",cells);
    }
    private static JSONObject goals(Api a,Object g) throws Exception {
        JSONObject o=new JSONObject();if(g==null)return o;
        for(String f:new String[]{"hudVerified","level","steps","observedAtMs"})o.put(f,a.get("Match3Goals",f,g));
        JSONArray targets=new JSONArray();
        for(Object t:(List<?>)a.get("Match3Goals","targets",g)) {
            JSONObject row=new JSONObject();for(String f:new String[]{"slot","kind","remaining","completed","visualId","mode","modeEvidence"})row.put(f,jsonValue(a.get("Match3Goals$Target",f,t)));targets.put(row);
        }
        return o.put("key",a.call("Match3Goals.key()",g)).put("targets",targets);
    }
    private static JSONArray moves(Api a,List<?> ranked) throws Exception {
        JSONArray out=new JSONArray();for(Object m:ranked)out.put(a.call("Match3RuleDiagnostics.action("+P+"Match3MoveValue)",null,m));return out;
    }
    private static JSONObject export(Api a,Step step,JSONObject entry) throws Exception {
        JSONObject out=new JSONObject().put("file",entry.getString("file")).put("status",step.status).put("reset",step.reset)
                .put("hud_status",step.hud).put("motion",step.motion).put("geometry",geometry(a,step.geometry))
                .put("position",position(a,step.board)).put("goals",goals(a,step.goals)).put("ranked_moves",moves(a,step.moves));
        for(String f:new String[]{"sequence","observed_at_ms","reset_before","delta_ms","missing_logged_frames_before"})if(entry.has(f))out.put(f,entry.get(f));
        return out;
    }
    private static Bitmap[] images(File dir,JSONArray entries) throws Exception {
        Bitmap[] images=new Bitmap[entries.length()];
        try {
            for(int i=0;i<images.length;i++) {
                JSONObject e=entries.getJSONObject(i);File file=new File(dir,e.getString("file"));
                assertTrue("relative frozen frame path",file.getCanonicalPath().startsWith(dir.getCanonicalPath()+File.separator));
                byte[] bytes=Files.readAllBytes(file.toPath());assertEquals("frame bytes",e.getString("sha256"),hash(bytes));
                images[i]=BitmapFactory.decodeByteArray(bytes,0,bytes.length);assertNotNull(images[i]);
                if(e.has("output_width"))assertEquals(e.getInt("output_width"),images[i].getWidth());
                if(e.has("output_height"))assertEquals(e.getInt("output_height"),images[i].getHeight());
            }
            return images;
        } catch(Exception|AssertionError failure){for(Bitmap image:images)if(image!=null)image.recycle();throw failure;}
    }
    private static JSONObject quality(Api a,Context app,JSONObject manifest,Bitmap[] images) throws Exception {
        JSONArray entries=manifest.getJSONArray("frames"),outputs=new JSONArray();
        Object hud=a.make("Match3HudReader.<init>(android.content.Context)",app);
        try {
            for(int i=0;i<images.length;i++) {
                JSONObject e=entries.getJSONObject(i);Step s=new Step();
                if(e.has("bounds")) {
                    JSONArray b=e.getJSONArray("bounds");int[] bounds={b.getInt(0),b.getInt(1),b.getInt(2),b.getInt(3)};
                    s.geometry=a.call("BoardGeometry.fromPercent(int,int,int,int,int[])",null,images[i].getWidth(),images[i].getHeight(),e.getInt("rows"),e.getInt("cols"),bounds);
                } else s.geometry=a.call("Match3Sampler.autoDetectGeometry(android.graphics.Bitmap)",null,images[i]);
                if(s.geometry!=null) {
                    Object sampler=a.make("Match3Sampler.<init>(android.content.Context,"+P+"BoardGeometry)",app,s.geometry);
                    try {
                        s.board=a.call("Match3Sampler.samplePosition(android.graphics.Bitmap)",sampler,images[i]);
                        s.goals=a.call("Match3HudReader.read(android.graphics.Bitmap,"+P+"BoardGeometry,long)",hud,images[i],s.geometry,0L);
                        s.moves=(List<?>)a.call("Match3MoveRanker.rankedMoves("+P+"Match3Position,"+P+"Match3Goals)",null,s.board,s.goals);
                        s.hud=(String)a.call("Match3HudReader.status()",hud);s.status="observed_and_ranked";
                    } finally {a.call("Match3Sampler.close()",sampler);}
                }
                assertEquals("nonboard declaration",e.optBoolean("no_board",false),s.geometry==null);
                outputs.put(export(a,s,e));
            }
        } finally {a.call("Match3HudReader.close()",hud);}
        return new JSONObject().put("workload","public-perception-quality-v1").put("outputs",outputs).put("frames",images.length);
    }
    private static JSONObject sequence(Api a,Context app,JSONObject m,Bitmap[] images) throws Exception {
        assertEquals("sequence-v1",m.getString("workload"));assertEquals(6,m.getInt("warmup_rounds"));assertEquals(15,m.getInt("measured_rounds"));assertEquals(1,m.getInt("repeats_per_frame"));
        JSONArray entries=m.getJSONArray("frames"),outputs=new JSONArray(),wall=new JSONArray(),worker=new JSONArray(),process=new JSONArray(),batches=new JSONArray();
        long[] stages=new long[4];Map<String,String> before=null;
        try(Pipeline pipeline=new Pipeline(a,app)) {
            for(int round=-6;round<15;round++) {
                if(round==0)before=Debug.getRuntimeStats();Step[] results=new Step[images.length];long[] batchStages=new long[4];
                long batchAt=SystemClock.elapsedRealtimeNanos(),processAt=Process.getElapsedCpuTime();
                for(int i=0;i<images.length;i++) {
                    JSONObject e=entries.getJSONObject(i);long[] stage=new long[4];long at=SystemClock.elapsedRealtimeNanos(),cpu=Debug.threadCpuTimeNanos();
                    results[i]=pipeline.process(images[i],e.getLong("observed_at_ms"),e.getBoolean("reset_before"),stage);
                    long elapsedWall=SystemClock.elapsedRealtimeNanos()-at,elapsedWorker=Debug.threadCpuTimeNanos()-cpu;
                    if(round>=0){wall.put(elapsedWall);worker.put(elapsedWorker);for(int n=0;n<4;n++){stages[n]+=stage[n];batchStages[n]+=stage[n];}}
                }
                long batchWall=SystemClock.elapsedRealtimeNanos()-batchAt,elapsedCpu=Process.getElapsedCpuTime()-processAt;
                if(round>=0){process.put(elapsedCpu);batches.put(new JSONObject().put("round",round).put("wall_ns",batchWall).put("process_cpu_ms",elapsedCpu).put("stage_worker_cpu_ns",new JSONArray(batchStages)));}
                if(round==14)for(int i=0;i<images.length;i++)outputs.put(export(a,results[i],entries.getJSONObject(i)));
            }
        }
        return new JSONObject().put("workload","public-perception-sequence-v1").put("warmup_rounds",6).put("measured_rounds",15)
                .put("repeats_per_frame",1).put("frames",images.length).put("repeats",images.length*15)
                .put("wall_ns",wall).put("worker_cpu_ns",worker).put("process_cpu_batches_ms",process).put("batches",batches)
                .put("stage_worker_cpu_total_ns",new JSONArray(stages)).put("stage_order",new JSONArray(new String[]{"geometry_and_reset","HUD","sample","rank"}))
                .put("runtime_before",new JSONObject(before)).put("runtime_after",new JSONObject(Debug.getRuntimeStats())).put("outputs",outputs);
    }

    private static Object part(Api a,Object p,String part,String field,Object defaultValue) throws Exception {
        Object value=a.get("GameProfile",part,p);return value==null?defaultValue:a.get(part.equals("playerLife")?"GameProfile$PlayerLifeData":"GameProfile$TemplateData",field,value);
    }
    private static JSONObject honor(Api a,Context app,JSONObject m,Bitmap[] images) throws Exception {
        Object p=a.call("GameProfile.load(android.content.Context)",null,app);JSONObject frozen=m.getJSONObject("profile_record");
        assertEquals(frozen.getString("name"),a.get("GameProfile","name",p));assertEquals(frozen.getString("version"),a.get("GameProfile","version",p));
        assertEquals(512,a.number("GameProfile","yoloxInputSize",p));assertEquals(true,a.get("GameProfile","minimapYolox",p));
        assertEquals(frozen.getDouble("confidence"),((Number)a.get("GameProfile","yoloxConfidence",p)).doubleValue(),.000001);
        float[] rois=(float[])a.get("GameProfile","rois",p),thresholds=(float[])a.get("GameProfile","yoloxClassThresholds",p);
        for(int i=0;i<rois.length;i++)assertEquals(frozen.getJSONArray("rois_xywh").getDouble(i),rois[i],.000001);
        for(int i=0;i<thresholds.length;i++)assertEquals(frozen.getJSONArray("class_thresholds").getDouble(i),thresholds[i],.000001);
        assertEquals(frozen.getInt("observation_age_budget_ms"),((int[])a.get("GameProfile","eventInts",p))[0]);
        ByteBuffer[] pixels=new ByteBuffer[images.length];for(int i=0;i<images.length;i++) {assertEquals(images[i].getWidth()*4,images[i].getRowBytes());pixels[i]=ByteBuffer.allocateDirect(images[i].getByteCount());images[i].copyPixelsToBuffer(pixels[i]);pixels[i].rewind();}
        String create="NativeBridge.nativeCreate(android.content.res.AssetManager,float[],int[],float[],int[],float,byte[],int,int,byte[],int,int,boolean,int,java.lang.String,java.lang.String,float,float,int[],float[],boolean,float[],int[],byte[],boolean,float[],int,long[],byte[],byte[],byte[])";
        long session=(long)a.call(create,null,app.getAssets(),rois,a.get("GameProfile","flags",p),a.get("GameProfile","tuning",p),a.get("GameProfile","eventInts",p),a.get("GameProfile","minConfidence",p),
                part(a,p,"enemyTemplate","rgba",null),part(a,p,"enemyTemplate","width",0),part(a,p,"enemyTemplate","height",0),
                part(a,p,"pingTemplate","rgba",null),part(a,p,"pingTemplate","width",0),part(a,p,"pingTemplate","height",0),
                a.get("GameProfile","minimapYolox",p),a.get("GameProfile","yoloxInputSize",p),a.get("GameProfile","yoloxParamAsset",p),a.get("GameProfile","yoloxBinAsset",p),a.get("GameProfile","yoloxConfidence",p),a.get("GameProfile","yoloxNms",p),a.get("GameProfile","yoloxClassKinds",p),thresholds,
                a.get("GameProfile","minimapLocatorEnabled",p),a.get("GameProfile","minimapLocatorFloats",p),a.get("GameProfile","minimapLocatorInts",p),a.get("GameProfile","minimapLocatorDescriptor",p),
                a.get("GameProfile","playerLife",p)!=null,part(a,p,"playerLife","roiAndThresholds",null),part(a,p,"playerLife","maxDhashDistance",0),part(a,p,"playerLife","hashes",null),part(a,p,"playerLife","states",null),part(a,p,"playerLife","luma",null),part(a,p,"playerLife","chroma",null));
        assertTrue(session!=0);JSONArray entries=m.getJSONArray("frames"),outputs=new JSONArray(),wall=new JSONArray(),worker=new JSONArray(),process=new JSONArray(),batches=new JSONArray();
        long inferences=0,reuses=0;long[] stages=new long[4];
        try {
            Object relation=a.get("GameProfile","relation",p);if(relation!=null)assertEquals(true,a.call("NativeBridge.nativeConfigureRelation(long,float[],int[])",null,session,a.get("GameProfile$RelationData","floats",relation),a.get("GameProfile$RelationData","ints",relation)));
            for(int round=-6;round<15;round++) {
                a.call("NativeBridge.nativeReset(long)",null,session);long[] before=(long[])a.call("NativeBridge.nativeReadWorkStats(long)",null,session);assertEquals(2,before[0]);
                long processAt=Process.getElapsedCpuTime(),batchAt=SystemClock.elapsedRealtimeNanos();
                for(int i=0;i<images.length;i++) {
                    long observed=1000000L+entries.getJSONObject(i).getLong("observed_at_ms"),at=SystemClock.elapsedRealtimeNanos(),cpu=Debug.threadCpuTimeNanos();
                    int[] packet=(int[])a.call("NativeBridge.nativeProcess(long,java.nio.ByteBuffer,int,int,int,long,long)",null,session,pixels[i],images[i].getWidth(),images[i].getHeight(),images[i].getWidth()*4,observed,observed);assertNotNull(packet);
                    Object raw=a.call("DiagnosticSnapshot.parse(long[])",null,a.call("NativeBridge.nativeReadDiagnosticSnapshot(long)",null,session));
                    Object frame=a.call("NativeBridge.parseFrameResult(int[],long)",null,packet,observed);
                    long elapsedWall=SystemClock.elapsedRealtimeNanos()-at,elapsedWorker=Debug.threadCpuTimeNanos()-cpu;
                    if(round>=0){wall.put(elapsedWall);worker.put(elapsedWorker);}
                    if(round==0) {
                        JSONArray observations=new JSONArray();for(long[] row:(long[][])a.get("DiagnosticSnapshot","observations",raw))observations.put(new JSONArray(row));
                        RectF roi=(RectF)a.get("NativeFrameResult","minimapRoi",frame);Object rel=a.get("NativeFrameResult","relation",frame);
                        JSONObject o=new JSONObject().put("file",entries.getJSONObject(i).getString("file")).put("observed_at_ms",observed).put("raw_observations",observations).put("native_packet",new JSONArray(packet))
                                .put("engine_age_ms",(long)a.get("DiagnosticSnapshot","engineAtMs",raw)-observed).put("locator_state",a.get("NativeFrameResult","locatorState",frame)).put("locator_score_milli",a.get("NativeFrameResult","locatorScoreMilli",frame))
                                .put("minimap_roi_xywh",new JSONArray(new float[]{roi.left,roi.top,roi.width(),roi.height()})).put("entity_count",((List<?>)a.get("NativeFrameResult","entities",frame)).size());
                        for(String f:new String[]{"state","event","reliable"})o.put("relation_"+f,a.get("NativeFrameResult$Relation",f,rel));outputs.put(o);
                    }
                }
                long elapsedCpu=Process.getElapsedCpuTime()-processAt,elapsedWall=SystemClock.elapsedRealtimeNanos()-batchAt;
                if(round>=0) {long[] after=(long[])a.call("NativeBridge.nativeReadWorkStats(long)",null,session);long infer=after[1]-before[1],reuse=after[2]-before[2];
                    inferences+=infer;reuses+=reuse;process.put(elapsedCpu);batches.put(new JSONObject().put("round",round).put("wall_ns",elapsedWall).put("process_cpu_ms",elapsedCpu).put("inferences",infer).put("cache_reuses",reuse));
                    stages[0]+=after[7]-before[7];stages[1]+=after[5]-before[5];stages[2]+=after[8]-before[8];stages[3]+=after[3]-before[3]+after[4]-before[4];}
            }
        } finally {a.call("NativeBridge.nativeDestroy(long)",null,session);}
        return new JSONObject().put("workload","public-honor-sequence-v1").put("warmup_rounds",6).put("measured_rounds",15).put("repeats_per_frame",1).put("frames",images.length).put("repeats",images.length*15)
                .put("inferences",inferences).put("cache_reuses",reuses).put("batches",batches).put("wall_ns",wall).put("worker_cpu_ns",worker).put("process_cpu_batches_ms",process)
                .put("stage_wall_total_us",new JSONArray(stages)).put("stage_order",new JSONArray(new String[]{"prepare","forward","decode","cache_compare_and_store"}))
                .put("observations",outputs).put("model_bin_sha256",a.get("GameProfile","minimapYoloxBinSha256",p)).put("profile_version",a.get("GameProfile","version",p)).put("locator_enabled",a.get("GameProfile","minimapLocatorEnabled",p)).put("input_size",512);
    }
    @Test public void installedOriginalApk() throws Exception {
        assertTrue("isolated emulator",android.os.Build.HARDWARE.contains("ranchu")||android.os.Build.HARDWARE.contains("goldfish"));
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext(),producer=InstrumentationRegistry.getInstrumentation().getContext();
        String mode=InstrumentationRegistry.getArguments().getString("replayMode","quality");
        File dir=app.getExternalFilesDir("original-apk-replay");assertNotNull(dir);
        byte[] bytes=Files.readAllBytes(new File(dir,"manifest.json").toPath());JSONObject m=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        assertTrue(m.getBoolean("frozen"));assertFalse(m.getBoolean("independent"));Api a=new Api(app,producer);
        Bitmap[] images=images(dir,m.getJSONArray("frames"));JSONObject out;
        try {
            switch(mode){case "quality":out=quality(a,app,m,images);break;case "sequence":out=sequence(a,app,m,images);break;case "honor":out=honor(a,app,m,images);break;default:throw new IllegalArgumentException("Unknown replay mode");}
        } finally {for(Bitmap image:images)image.recycle();}
        out.put("apk_sha256",a.apkSha).put("contract_sha256",a.contractSha).put("manifest_sha256",hash(bytes)).put("independent",false).put("profiling",false)
                .put("scope","installed original APK processing; no capture, UI, audio, admission lifecycle, independent accuracy or physical heat")
                .put("producer_sha256",hash(new File(producer.getApplicationInfo().sourceDir)));
        Files.write(new File(dir,"result.json").toPath(),out.toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
