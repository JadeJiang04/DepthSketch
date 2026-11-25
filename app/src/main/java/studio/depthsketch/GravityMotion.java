package studio.depthsketch;

import android.content.Context;
import android.hardware.*;
import android.os.*;
import android.view.Surface;
import android.view.WindowManager;

/** Neutral-relative gravity tilt with damping. No sensor or animation loop while invisible. */
final class GravityMotion implements SensorEventListener {
    interface Listener{void tilt(float pitch,float yaw);}
    private final SensorManager manager;
    private final WindowManager windows;
    private final Sensor sensor;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Listener listener;
    private boolean active,neutral,pending;
    private float zeroRoll,zeroPitch,targetPitch,targetYaw,pitch,yaw;
    private final float[] filtered=new float[3];
    float strength=6;
    private final Runnable tick=new Runnable(){public void run(){
        pending=false;if(!active)return;pitch+=(targetPitch-pitch)*.2f;yaw+=(targetYaw-yaw)*.2f;listener.tilt(pitch,yaw);
        if(Math.abs(targetPitch-pitch)+Math.abs(targetYaw-yaw)>.015f)schedule();
    }};
    GravityMotion(Context context,Listener listener){
        manager=(SensorManager)context.getSystemService(Context.SENSOR_SERVICE);windows=(WindowManager)context.getSystemService(Context.WINDOW_SERVICE);this.listener=listener;
        Sensor gravity=manager.getDefaultSensor(Sensor.TYPE_GRAVITY);sensor=gravity!=null?gravity:manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }
    boolean supported(){return sensor!=null;}
    void start(){if(active)return;active=true;neutral=false;if(sensor!=null)manager.registerListener(this,sensor,SensorManager.SENSOR_DELAY_GAME);}
    void stop(){active=false;manager.unregisterListener(this);handler.removeCallbacks(tick);pending=false;}
    void recenter(){neutral=false;targetPitch=targetYaw=pitch=yaw=0;listener.tilt(0,0);}
    private void schedule(){if(active&&!pending){pending=true;handler.postDelayed(tick,33);}}
    @Override public void onSensorChanged(SensorEvent event){accept(event.values[0],event.values[1],event.values[2],windows.getDefaultDisplay().getRotation());}
    void accept(float x,float y,float z,int rotation){
        if(!active)return;
        if(rotation==Surface.ROTATION_90){float a=x;x=-y;y=a;}else if(rotation==Surface.ROTATION_180){x=-x;y=-y;}else if(rotation==Surface.ROTATION_270){float a=x;x=y;y=-a;}
        if(!neutral){filtered[0]=x;filtered[1]=y;filtered[2]=z;}else{filtered[0]+=(x-filtered[0])*.2f;filtered[1]+=(y-filtered[1])*.2f;filtered[2]+=(z-filtered[2])*.2f;}
        float roll=(float)Math.atan2(filtered[0],Math.sqrt(filtered[1]*filtered[1]+filtered[2]*filtered[2]));
        float elevation=(float)Math.atan2(filtered[1],filtered[2]);
        if(!neutral){zeroRoll=roll;zeroPitch=elevation;neutral=true;}
        targetYaw=clamp((roll-zeroRoll)*57.29578f*strength/25f,strength);
        targetPitch=clamp(wrap((elevation-zeroPitch)*57.29578f)*strength/25f,strength);
        if(Math.abs(targetPitch-pitch)+Math.abs(targetYaw-yaw)>.015f)schedule();
    }
    private static float wrap(float a){return (a+540)%360-180;}
    private static float clamp(float value,float bound){return Math.max(-bound,Math.min(bound,value));}
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}
}
