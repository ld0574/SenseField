package com.openkhub.sensefield;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.*;
import java.util.Objects;

/** Test APK only. Holds a bounded CPU lock for a same-signature, nonce-bound caller. */
public final class Match3SoakWakeService extends Service {
    static final String DESCRIPTOR="com.openkhub.sensefield.test.SoakWake";
    static final int ACQUIRE=IBinder.FIRST_CALL_TRANSACTION, RELEASE=ACQUIRE+1, STATUS=ACQUIRE+2;
    private PowerManager.WakeLock wake;
    private String nonce;
    private int owner=-1;
    static volatile boolean completed;
    static volatile boolean verifiedRelease;
    private final Handler lifetime=new Handler(Looper.getMainLooper());
    private final Runnable expire=()->{release();completed=true;stopSelf();};
    private final Binder binder=new Binder() {
        @Override protected synchronized boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
            if(code<ACQUIRE || code>STATUS)return super.onTransact(code,data,reply,flags);
            data.enforceInterface(DESCRIPTOR);
            int uid=Binder.getCallingUid();
            if(getPackageManager().checkSignatures(uid,getApplicationInfo().uid)!=PackageManager.SIGNATURE_MATCH)
                throw new SecurityException("same-signature test caller required");
            String supplied=data.readString();
            if(code==ACQUIRE) {
                long timeout=data.readLong();
                if(supplied==null || supplied.length()<32 || timeout<1000 || timeout>960000)
                    throw new IllegalArgumentException("bounded wake request required");
                if(wake!=null)throw new IllegalStateException("wake already acquired");
                wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"SenseField:PrivateSoakHelper");
                wake.setReferenceCounted(false);nonce=supplied;owner=uid;wake.acquire(timeout);
                lifetime.removeCallbacks(expire);lifetime.postDelayed(expire,timeout);
            } else {
                if(owner!=uid || !Objects.equals(nonce,supplied))throw new SecurityException("wake owner mismatch");
                if(code==RELEASE){release();verifiedRelease=true;completed=true;}
            }
            reply.writeNoException();reply.writeInt(wake!=null&&wake.isHeld()?1:0);
            reply.writeInt(android.os.Process.myUid());
            return true;
        }
    };
    private void release(){synchronized(binder){if(wake!=null&&wake.isHeld())wake.release();wake=null;nonce=null;owner=-1;}}
    // Self-targeted instrumentation binds first in the test UID. No background start
    // exemption or device-setting change is needed. Bootstrap itself holds no lock.
    @Override public void onCreate(){super.onCreate();completed=false;verifiedRelease=false;lifetime.postDelayed(expire,30000);}
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public boolean onUnbind(Intent intent){release();lifetime.removeCallbacks(expire);stopSelf();return false;}
    @Override public void onDestroy(){lifetime.removeCallbacks(expire);release();super.onDestroy();}
}
