package com.openkhub.sensefield;

import android.content.*;
import android.os.*;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Releases the helper's lock and binding; no UiAutomation or shell identity. */
final class Match3SoakWakeClient implements AutoCloseable {
    private final Context app;
    private final String nonce=UUID.randomUUID().toString();
    private final CountDownLatch ready=new CountDownLatch(1);
    private volatile IBinder binder;
    private boolean bound;
    private boolean acquired;
    int helperUid=-1;
    private final ServiceConnection connection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder service){binder=service;ready.countDown();}
        @Override public void onServiceDisconnected(ComponentName name){binder=null;}
        @Override public void onNullBinding(ComponentName name){ready.countDown();}
        @Override public void onBindingDied(ComponentName name){binder=null;ready.countDown();}
    };
    Match3SoakWakeClient(Context app,String testPackage,long timeout) throws Exception {
        this.app=app.getApplicationContext();
        Intent intent=new Intent().setComponent(new ComponentName(testPackage,Match3SoakWakeService.class.getName()));
        bound=this.app.bindService(intent,connection,Context.BIND_AUTO_CREATE);
        try {
            if(!bound || !ready.await(15,TimeUnit.SECONDS) || binder==null)
                throw new IllegalStateException("test wake helper did not bind: accepted="+bound+", component="+intent.getComponent());
            if(!request(Match3SoakWakeService.ACQUIRE,timeout))throw new IllegalStateException("helper wake not acquired");
            acquired=true;
            if(helperUid==android.os.Process.myUid())throw new IllegalStateException("helper must own its permission in a separate UID");
        } catch(Exception error){close();throw error;}
    }
    private boolean request(int code,long timeout) throws RemoteException {
        IBinder service=binder;if(service==null)throw new IllegalStateException("wake helper disconnected");
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try {
            data.writeInterfaceToken(Match3SoakWakeService.DESCRIPTOR);data.writeString(nonce);
            if(code==Match3SoakWakeService.ACQUIRE)data.writeLong(timeout);
            if(!service.transact(code,data,reply,0))throw new IllegalStateException("wake helper transaction rejected");
            reply.readException();boolean held=reply.readInt()!=0;helperUid=reply.readInt();return held;
        } finally {reply.recycle();data.recycle();}
    }
    boolean isHeld() throws RemoteException{return request(Match3SoakWakeService.STATUS,0);}
    @Override public void close() throws Exception {
        try {if(acquired && request(Match3SoakWakeService.RELEASE,0))throw new IllegalStateException("wake not released");}
        finally {if(bound){app.unbindService(connection);bound=false;}binder=null;acquired=false;}
    }
}
