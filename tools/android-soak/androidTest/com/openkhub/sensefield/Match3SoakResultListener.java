package com.openkhub.sensefield;

import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.*;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

/** Records actual JUnit callbacks locally, independently of the shell result watcher. */
public final class Match3SoakResultListener extends RunListener {
    private final JSONArray events=new JSONArray();
    private File directory;
    private int assumptions;
    private void event(String name,Description description) throws Exception {
        events.put(new JSONObject().put("event",name).put("test",description.toString()));
    }
    @Override public void testRunStarted(Description description) throws Exception {
        android.os.Bundle args=InstrumentationRegistry.getArguments();
        boolean emulator=android.os.Build.HARDWARE.contains("ranchu")||android.os.Build.HARDWARE.contains("goldfish");
        if(!"true".equals(args.getString(emulator?"authorizedEmulatorAcceptance":"authorizedPhoneAcceptance")))
            throw new IllegalStateException("authorized test for this device kind only");
        String run=args.getString("soakRun");
        if(run==null || !run.matches("[a-z0-9-]+"))throw new IllegalArgumentException("unique test run required");
        directory=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir("match3-soak-junit"),run);
        if(!directory.mkdirs())throw new IllegalStateException("fresh JUnit output required");
        event("run_started",description);
    }
    @Override public void testStarted(Description d) throws Exception {event("test_started",d);}
    @Override public void testFinished(Description d) throws Exception {event("test_finished",d);}
    @Override public void testIgnored(Description d) throws Exception {event("test_ignored",d);}
    @Override public void testAssumptionFailure(Failure failure) {
        assumptions++;
        try {event("assumption_failed",failure.getDescription());}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    @Override public void testFailure(Failure failure) throws Exception {
        event("test_failure",failure.getDescription());events.put(new JSONObject().put("failure",failure.getTrace()));
    }
    @Override public void testRunFinished(Result result) throws Exception {
        android.os.Bundle args=InstrumentationRegistry.getArguments();
        JSONObject report=new JSONObject().put("format","actual-junit-result-v1").put("complete",true)
            .put("source_sha256",args.getString("match3SourceSha256")).put("harness_sha256",args.getString("phoneHarnessSha256"))
            .put("run",args.getString("soakRun")).put("executed",result.getRunCount()).put("failed",result.getFailureCount())
            .put("ignored",result.getIgnoreCount()).put("assumption_failed",assumptions)
            .put("successful",result.wasSuccessful()).put("junit_runtime_ms",result.getRunTime()).put("events",events);
        File temporary=new File(directory,"junit.json.part"),target=new File(directory,"junit.json");
        Files.write(temporary.toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
        if(!temporary.renameTo(target))throw new IllegalStateException("JUnit receipt not saved");
    }
}
