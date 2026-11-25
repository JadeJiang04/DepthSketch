package studio.depthsketch;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Actual point segmentation/depth import, object editing/persistence, wallpaper + tilt integration. */
final class AdditionChecks {
    private final Instrumentation test;
    private final MainActivity main;
    private final Bundle result=new Bundle();
    private long down;
    AdditionChecks(Instrumentation test,MainActivity main){this.test=test;this.main=main;}
    Bundle run()throws Exception{
        long started=SystemClock.elapsedRealtime();float[] old=main.scene.depth;
        ui(()->main.estimate(null));until(()->!main.busy&&main.scene.depth!=null&&main.scene.depth!=old,150000,"background depth");
        Bitmap background=main.scene.image;
        // Publish a source photo owned by this test into MediaStore; same URI path as the picker.
        ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,"depthsketch-test-source.jpg");values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/DepthSketchTests");
        Uri source=main.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);check(source!=null,"cannot create test source");
        try(InputStream in=main.getAssets().open("sample.jpg");OutputStream out=main.getContentResolver().openOutputStream(source)){byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);}
        Instrumentation.ActivityMonitor monitor=test.addMonitor(CutoutActivity.class.getName(),null,false);
        ui(()->main.importCutout(source));CutoutActivity cut=(CutoutActivity)test.waitForMonitorWithTimeout(monitor,15000);test.removeMonitor(monitor);check(cut!=null,"cutout activity did not open");
        until(()->!cut.busy&&cut.picker.image!=null,180000,"source depth and MobileSAM encoder");
        float[] point=cut.picker.screenPoint(.88f,.90f);tap(cut.picker,point[0],point[1]);until(()->!cut.busy,60000,"positive point mask");
        Button add=(Button)findText(cut.getWindow().getDecorView(),"加入画布");check(add!=null&&add.isEnabled(),"positive point produced no mask");
        ui(()->findText(cut.getWindow().getDecorView(),"排除背景").performClick());point=cut.picker.screenPoint(.5f,.12f);tap(cut.picker,point[0],point[1]);until(()->!cut.busy,60000,"negative point refinement");
        screenshot("05-point-segmentation.png");ui(add::performClick);
        until(()->!main.busy&&main.scene.cutouts.size()==1,30000,"RGBA+depth import");
        check(main.scene.image==background,"import replaced original background");Scene.Cutout object=main.scene.cutouts.get(0);
        check(object.width==object.image.getWidth()&&object.height==object.image.getHeight()&&object.depth.length==object.width*object.height,"cutout RGB/depth misaligned");
        int opaque=0,clear=0,inside=0;float depthLo=1,depthHi=0;
        for(int y=0;y<object.height;y++)for(int x=0;x<object.width;x++){
            int a=object.image.getPixel(x,y)>>>24;if(a>200){opaque++;inside=y*object.width+x;depthLo=Math.min(depthLo,object.depth[inside]);depthHi=Math.max(depthHi,object.depth[inside]);}if(a==0)clear++;
        }
        check(opaque>30&&clear>30,"cutout does not preserve transparent mask");check(depthHi-depthLo>.0001f,"object depth was flattened");
        pass("real_point_segmentation_rgba_depth_crop");
        // Pick an opaque pixel near the object center; dragging must move the object, not paint.
        int best=inside;double distance=Double.MAX_VALUE;
        for(int y=0;y<object.height;y+=3)for(int x=0;x<object.width;x+=3)if((object.image.getPixel(x,y)>>>24)>200){double d=Math.hypot(x-object.width/2.0,y-object.height/2.0);if(d<distance){distance=d;best=y*object.width+x;}}
        float u=(best%object.width+.5f)/object.width,v=(best/object.width+.5f)/object.height;
        AtomicReference<float[]> position=new AtomicReference<>();ui(()->{main.canvas.renderer.updateCamera();position.set(main.canvas.renderer.project(new float[]{object.x+(u-.5f)*2*object.aspect()*object.scale,object.y+(.5f-v)*2*object.scale,object.z},null));});
        float[] p=position.get();float oldX=object.x;event(main.canvas,MotionEvent.ACTION_DOWN,p[0],p[1]);event(main.canvas,MotionEvent.ACTION_MOVE,p[0]+90,p[1]-25);event(main.canvas,MotionEvent.ACTION_UP,p[0]+90,p[1]-25);
        check(Math.abs(object.x-oldX)>.01f,"object XY dragging failed");
        ui(()->findText(main.getWindow().getDecorView(),"撤回").performClick());check(main.scene.cutouts.size()==1&&Math.abs(main.scene.cutouts.get(0).x-oldX)<.001,"object undo failed");
        ui(()->findText(main.getWindow().getDecorView(),"重做").performClick());ui(()->main.onCutoutSelected(0));
        slide("对象前后",.78f);slide("对象大小",.38f);slide("表面起伏",.65f);slide("对象 X",.56f);slide("对象 Y",.58f);slide("对象 Z",.57f);
        Scene.Cutout edited=main.scene.cutouts.get(0);check(edited.rx>10&&edited.ry>10&&edited.rz>10&&edited.scale>.6f,"object inspector edits failed");
        screenshot("06-depth-cutout-editor.png");pass("object_drag_undo_xyz_scale_relief");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();ProjectStore.write(bytes,main.scene);Scene restored=ProjectStore.read(new ByteArrayInputStream(bytes.toByteArray()));
        check(restored.cutouts.size()==1&&restored.cutouts.get(0).image.hasAlpha(),"project lost object RGBA");
        check(restored.cutouts.get(0).depth.length==edited.depth.length&&Math.abs(restored.cutouts.get(0).rx-edited.rx)<.001,"project lost depth/transform");
        for(int i=0;i<edited.depth.length;i+=73)check(restored.cutouts.get(0).depth[i]==edited.depth[i],"depth pixels changed during save");pass("project_v2_cutout_roundtrip");
        ui(()->{synchronized(main.scene){Scene.Cutout c=main.scene.cutouts.get(0);c.scale=.25f;c.x=.1f;c.y=.15f;c.rx=10;c.ry=-12;c.rz=6;}});
        Instrumentation.ActivityMonitor previewMonitor=test.addMonitor(WallpaperPreviewActivity.class.getName(),null,false);
        long appliedBefore=main.getSharedPreferences(WallpaperFiles.PREFS,0).getLong("updated",0);
        ui(main::publishWallpaper);WallpaperPreviewActivity preview=(WallpaperPreviewActivity)test.waitForMonitorWithTimeout(previewMonitor,30000);test.removeMonitor(previewMonitor);check(preview!=null,"wallpaper preview not opened");
        until(()->findDescription(preview.getWindow().getDecorView(),"重力动态壁纸预览")!=null,15000,"wallpaper GPU preview");SystemClock.sleep(700);
        Scene published=WallpaperFiles.load(main,true);check(published.cutouts.size()==1&&published.wallpaper&&published.selectedCutout==-1,"published wallpaper lost scene");
        check(main.getSharedPreferences(WallpaperFiles.PREFS,0).getLong("updated",0)==appliedBefore,"preview changed active wallpaper");
        screenshot("07-wallpaper-preview.png");
        android.content.pm.ServiceInfo service=main.getPackageManager().getServiceInfo(new ComponentName(main,DepthWallpaperService.class),0);
        check("android.permission.BIND_WALLPAPER".equals(service.permission),"wallpaper service permission missing");
        check(WallpaperFiles.previewIntent(main).resolveActivity(main.getPackageManager())!=null,"system wallpaper preview missing");pass("wallpaper_publish_preview_service");
        AtomicInteger events=new AtomicInteger();AtomicReference<Float> max=new AtomicReference<>(0f);
        AtomicReference<GravityMotion> sensor=new AtomicReference<>();
        ui(()->{GravityMotion motion=new GravityMotion(main,(pitch,yaw)->{events.incrementAndGet();max.set(Math.max(max.get(),Math.abs(pitch)+Math.abs(yaw)));});sensor.set(motion);motion.start();motion.accept(0,9.8f,0,Surface.ROTATION_0);for(int i=0;i<15;i++)motion.accept(3,9,2,Surface.ROTATION_0);});
        SystemClock.sleep(250);ui(()->sensor.get().stop());check(max.get()>.05f&&max.get()<=12.01f,"gravity damping/limits failed");int stopped=events.get();
        ui(()->sensor.get().accept(9,0,0,Surface.ROTATION_0));SystemClock.sleep(180);check(events.get()==stopped,"hidden gravity controller still animates");pass("gravity_tilt_damping_stop");
        // Open the real Android live wallpaper chooser. This exercises the service's actual EGL Surface.
        ui(()->findText(preview.getWindow().getDecorView(),"设为动态壁纸").performClick());SystemClock.sleep(1800);screenshot("08-system-live-wallpaper.png");pass("system_live_wallpaper_preview_opened");
        until(()->nativeNode("Set wallpaper")!=null&&nativeNode("Set wallpaper").isEnabled(),10000,"native set wallpaper button");
        nativeClick(nativeNode("Set wallpaper"));
        long nativeDeadline=SystemClock.elapsedRealtime()+12000;
        while(main.getSharedPreferences(WallpaperFiles.PREFS,0).getLong("updated",0)==appliedBefore&&SystemClock.elapsedRealtime()<nativeDeadline){
            android.view.accessibility.AccessibilityNodeInfo home=nativeNode("Home screen");if(home!=null)nativeClick(home);SystemClock.sleep(150);
        }
        check(main.getSharedPreferences(WallpaperFiles.PREFS,0).getLong("updated",0)>appliedBefore,"system confirmation did not apply preview");
        Scene active=WallpaperFiles.load(main);check(active.cutouts.size()==1&&Math.abs(active.cutouts.get(0).scale-.25f)<.001,"active wallpaper does not match preview");
        check(java.util.Arrays.equals(hash(new File(main.getFilesDir(),"wallpaper.dsketch")),hash(new File(main.getFilesDir(),"wallpaper-preview.dsketch"))),"active/draft file mismatch");
        test.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);SystemClock.sleep(1200);screenshot("09-home-live-wallpaper.png");
        pass("system_home_wallpaper_applied_after_confirmation");
        result.putLong("total_ms",SystemClock.elapsedRealtime()-started);result.putString("result","PASS");return result;
    }
    private void ui(Runnable action){test.runOnMainSync(action);test.waitForIdleSync();}
    interface Condition{boolean ready();}
    private static void until(Condition c,long ms,String name)throws Exception{long end=SystemClock.elapsedRealtime()+ms;while(!c.ready()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);check(c.ready(),"timeout: "+name);}
    private void event(View view,int action,float x,float y){ui(()->{long time=SystemClock.uptimeMillis();if(action==MotionEvent.ACTION_DOWN)down=time;MotionEvent e=MotionEvent.obtain(down,time,action,x,y,0);view.dispatchTouchEvent(e);e.recycle();});}
    private void tap(View view,float x,float y){event(view,MotionEvent.ACTION_DOWN,x,y);event(view,MotionEvent.ACTION_UP,x,y);}
    private void slide(String label,float fraction){View tab=findDescription(main.getWindow().getDecorView(),"编辑 "+label);if(tab!=null)ui(tab::performClick);View view=findDescription(main.getWindow().getDecorView(),label);check(view instanceof SeekBar,"missing slider "+label);SystemClock.sleep(100);SeekBar bar=(SeekBar)view;float width=bar.getWidth()-bar.getPaddingLeft()-bar.getPaddingRight(),y=bar.getHeight()/2f,target=bar.getPaddingLeft()+fraction*width,start=bar.getPaddingLeft()+bar.getProgress()/(float)bar.getMax()*width;event(bar,MotionEvent.ACTION_DOWN,start,y);event(bar,MotionEvent.ACTION_MOVE,Math.min(bar.getWidth()-bar.getPaddingRight(),target+main.dp(30)),y);event(bar,MotionEvent.ACTION_MOVE,target,y);event(bar,MotionEvent.ACTION_UP,target,y);}
    private static View findText(View view,String value){if(view instanceof TextView&&((TextView)view).getText().toString().equals(value))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findText(((ViewGroup)view).getChildAt(i),value);if(found!=null)return found;}return null;}
    private static View findDescription(View view,String value){if(value.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findDescription(((ViewGroup)view).getChildAt(i),value);if(found!=null)return found;}return null;}
    private void pass(String name){result.putString(name,"PASS");Bundle update=new Bundle();update.putString("stream","PASS "+name+"\n");test.sendStatus(0,update);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private void screenshot(String name)throws Exception{SystemClock.sleep(250);Bitmap bitmap=test.getUiAutomation().takeScreenshot();File folder=main.getExternalFilesDir("checks");folder.mkdirs();try(OutputStream out=new FileOutputStream(new File(folder,name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();}
    private android.view.accessibility.AccessibilityNodeInfo nativeNode(String text){android.view.accessibility.AccessibilityNodeInfo root=test.getUiAutomation().getRootInActiveWindow();if(root==null)return null;for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text))if(node.getText()!=null&&node.getText().toString().equals(text))return node;return null;}
    private static void nativeClick(android.view.accessibility.AccessibilityNodeInfo node){while(node!=null&&!node.isClickable())node=node.getParent();check(node!=null&&node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK),"cannot click native wallpaper control");}
    private static byte[] hash(File file)throws Exception{java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);}return digest.digest();}
}
