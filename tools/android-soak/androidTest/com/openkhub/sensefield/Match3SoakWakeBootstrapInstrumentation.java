package com.openkhub.sensefield;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Test-UID owner of the bound helper; never opens an activity or UiAutomation. */
public final class Match3SoakWakeBootstrapInstrumentation extends Instrumentation {
    private Bundle args;
    private File directory;
    private void save(String name,JSONObject value) throws Exception {
        File temporary=new File(directory,name+".part"),target=new File(directory,name);
        Files.write(temporary.toPath(),value.toString(2).getBytes(StandardCharsets.UTF_8));
        if(!temporary.renameTo(target))throw new IllegalStateException("bootstrap receipt not saved");
    }
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);args=arguments;start();}
    @Override public void onStart(){
        boolean bound=false;Context context=getTargetContext().getApplicationContext();
        CountDownLatch ready=new CountDownLatch(1);AtomicBoolean connected=new AtomicBoolean();
        ServiceConnection connection=new ServiceConnection(){
            @Override public void onServiceConnected(ComponentName name,IBinder binder){connected.set(true);ready.countDown();}
            @Override public void onServiceDisconnected(ComponentName name){connected.set(false);}
            @Override public void onNullBinding(ComponentName name){ready.countDown();}
        };
        Throwable error=null;Bundle result=new Bundle();
        try {
            if(!"true".equals(args.getString("authorizedPhoneAcceptance")))throw new IllegalStateException("authorized test only");
            String run=args.getString("soakRun");
            if(run==null||!run.matches("[a-z0-9-]+"))throw new IllegalArgumentException("unique run required");
            directory=new File(context.getExternalFilesDir("match3-soak-bootstrap"),run);
            if(!directory.mkdirs())throw new IllegalStateException("fresh bootstrap output required");
            int seconds=Integer.parseInt(args.getString("soakSeconds"));
            if(seconds!=20&&seconds!=900)throw new IllegalArgumentException("bounded duration required");
            bound=context.bindService(new Intent(context,Match3SoakWakeService.class),connection,Context.BIND_AUTO_CREATE);
            if(!bound||!ready.await(15,TimeUnit.SECONDS)||!connected.get())throw new IllegalStateException("self helper bind failed");
            save("ready.json",new JSONObject().put("helper_uid",android.os.Process.myUid()).put("run",run));
            long until=SystemClock.elapsedRealtime()+(seconds+60)*1000L;
            while(!Match3SoakWakeService.completed){
                if(!connected.get()||SystemClock.elapsedRealtime()>=until)throw new IllegalStateException("helper disconnected or timed out before verified release");
                SystemClock.sleep(100);
            }
            if(!Match3SoakWakeService.verifiedRelease)throw new IllegalStateException("helper lifetime expired without verified release");
        } catch(Throwable failure){error=failure;}
        finally {
            try{if(bound)context.unbindService(connection);}catch(Throwable failure){if(error==null)error=failure;}
            try {
                if(directory!=null)save("result.json",new JSONObject().put("passed",error==null)
                    .put("helper_uid",android.os.Process.myUid()).put("run",args.getString("soakRun"))
                    .put("error",error==null?"":error.toString()));
            } catch(Throwable failure){if(error==null)error=failure;}
            result.putString("stream",error==null?"helper released and unbound\n":"helper failed: "+error+"\n");
            finish(error==null?-1:0,result);
        }
    }
}
