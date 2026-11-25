package studio.depthsketch;

import android.content.*;
import android.opengl.GLES20;
import android.os.*;
import android.service.wallpaper.WallpaperService;
import android.view.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DepthWallpaperService extends WallpaperService {
    @Override public Engine onCreateEngine(){return new DepthEngine();}
    final class DepthEngine extends Engine implements SharedPreferences.OnSharedPreferenceChangeListener {
        private final HandlerThread thread=new HandlerThread("DepthWallpaperGL");
        private Handler render;
        private WallpaperEgl egl;
        private GpuScene gpu;
        private volatile Scene scene;
        private GravityMotion motion;
        private volatile boolean visible,surfaceReady,destroyed;
        private volatile int width=1,height=1;
        private volatile float basePitch,baseYaw;
        private boolean loggedFrame;
        private final AtomicBoolean pending=new AtomicBoolean();
        @Override public void onCreate(SurfaceHolder holder){
            super.onCreate(holder);thread.start();render=new Handler(thread.getLooper());
            motion=new GravityMotion(DepthWallpaperService.this,(pitch,yaw)->{Scene current=scene;if(current!=null){synchronized(current){current.pitch=basePitch+pitch;current.yaw=baseYaw+yaw;}requestFrame();}});
            getSharedPreferences(WallpaperFiles.PREFS,0).registerOnSharedPreferenceChangeListener(this);
        }
        @Override public void onSurfaceCreated(SurfaceHolder holder){
            super.onSurfaceCreated(holder);surfaceReady=true;
            if(visible)configureMotion();
            render.post(()->{if(destroyed||!surfaceReady||!holder.getSurface().isValid())return;try{closeEgl();egl=new WallpaperEgl();egl.create(holder.getSurface());loadScene();}catch(Exception e){android.util.Log.e("DepthSketch","Wallpaper surface failed",e);closeEgl();}});
        }
        private void loadScene()throws Exception{
            Scene loaded=WallpaperFiles.load(DepthWallpaperService.this,isPreview());basePitch=loaded.pitch;baseYaw=loaded.yaw;scene=loaded;gpu=new GpuScene(loaded);gpu.init();loggedFrame=false;requestFrame();
        }
        @Override public void onSurfaceChanged(SurfaceHolder holder,int format,int w,int h){super.onSurfaceChanged(holder,format,w,h);width=w;height=h;requestFrame();}
        @Override public void onVisibilityChanged(boolean value){
            android.util.Log.i("DepthWallpaper","visible="+value+" preview="+isPreview());
            visible=value;if(value){configureMotion();requestFrame();}else{motion.stop();}
        }
        private void configureMotion(){
            SharedPreferences prefs=getSharedPreferences(WallpaperFiles.PREFS,0);motion.strength=prefs.getFloat("strength",6);
            if(visible&&surfaceReady&&prefs.getBoolean("gravity",true))motion.start();else{motion.stop();motion.recenter();}
        }
        private void requestFrame(){
            if(!visible||!surfaceReady||destroyed||render==null||!pending.compareAndSet(false,true))return;
            render.post(()->{pending.set(false);if(!visible||!surfaceReady||destroyed||egl==null||gpu==null)return;
                try{GLES20.glViewport(0,0,width,height);gpu.draw(width,height);if(!egl.swap())throw new IllegalStateException("EGL swap failed");if(!loggedFrame){loggedFrame=true;android.util.Log.i("DepthWallpaper","FRAME_OK "+width+"x"+height+" objects="+scene.cutouts.size());}}
                catch(Exception e){android.util.Log.e("DepthSketch","Wallpaper draw failed",e);closeEgl();}
            });
        }
        @Override public void onSharedPreferenceChanged(SharedPreferences prefs,String key){
            configureMotion();if((key.equals("updated")&&!isPreview())||(key.equals("preview_updated")&&isPreview())){render.post(()->{try{if(egl!=null){closeEgl();if(surfaceReady){egl=new WallpaperEgl();egl.create(getSurfaceHolder().getSurface());loadScene();}}}catch(Exception e){android.util.Log.e("DepthSketch","Wallpaper reload failed",e);closeEgl();}});}
            else{Scene current=scene;if(current!=null)WallpaperFiles.settings(DepthWallpaperService.this,current);requestFrame();}
        }
        @Override public void onSurfaceDestroyed(SurfaceHolder holder){surfaceReady=false;motion.stop();render.post(this::closeEgl);super.onSurfaceDestroyed(holder);}
        private void closeEgl(){if(egl!=null)egl.close();egl=null;gpu=null;}
        @Override public void onDestroy(){destroyed=true;motion.stop();getSharedPreferences(WallpaperFiles.PREFS,0).unregisterOnSharedPreferenceChangeListener(this);render.post(()->{closeEgl();thread.quitSafely();});super.onDestroy();}
    }
}
