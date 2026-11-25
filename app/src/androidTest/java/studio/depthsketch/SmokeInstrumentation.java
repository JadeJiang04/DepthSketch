package studio.depthsketch;

import android.app.*;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicReference;

/** Device integration checks: real inference, pointer strokes, selection, transforms and project I/O. */
public final class SmokeInstrumentation extends Instrumentation {
    private MainActivity activity;
    private long gestureTime;
    private final Bundle results=new Bundle();
    private boolean profile;
    private boolean additions;
    private String latency;
    private String scenePreviews;
    private boolean interactions;
    private boolean materials;
    @Override public void onCreate(Bundle args){super.onCreate(args);profile="true".equals(args.getString("profile"));additions="true".equals(args.getString("additions"));latency=args.getString("latency");scenePreviews=args.getString("scene_previews");interactions="true".equals(args.getString("interactions"));materials="true".equals(args.getString("materials"));start();}
    @Override public void onStart(){
        try {
            if(scenePreviews!=null){finish(Activity.RESULT_OK,new ScenePreviewOutputs(this).run());return;}
            // Standalone diagnostics: never open the editor or replace the user's wallpaper/autosave.
            if(latency!=null){finish(Activity.RESULT_OK,new CutoutLatencyChecks(this).run(latency));return;}
            Intent intent=new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity=(MainActivity)startActivitySync(intent);waitForIdleSync();
            while(activity.busy)SystemClock.sleep(100);
            if(materials){finish(Activity.RESULT_OK,new MaterialImportChecks(this,activity).run());return;}
            if(interactions){finish(Activity.RESULT_OK,new CanvasInteractionChecks(this,activity).run());return;}
            if(profile){finish(Activity.RESULT_OK,PerformanceChecks.run(this,activity));return;}
            if(additions){finish(Activity.RESULT_OK,new AdditionChecks(this,activity).run());return;}
            checkpoint("startup");screenshot("01-welcome.png");
            long start=SystemClock.elapsedRealtime();
            float[] oldDepth=activity.scene.depth;
            main(()->activity.estimate(null));
            long deadline=SystemClock.elapsedRealtime()+150000;
            while((activity.scene.depth==oldDepth||activity.busy)&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(200);
            waitForIdleSync();check(activity.scene.depth!=null&&activity.scene.depth!=oldDepth,"real depth inference did not finish");
            results.putLong("inference_wall_ms",SystemClock.elapsedRealtime()-start);
            float lo=1,hi=0;for(float d:activity.scene.depth){check(Float.isFinite(d),"non-finite depth");lo=Math.min(lo,d);hi=Math.max(hi,d);}
            check(hi-lo>.5f,"depth map is constant");checkpoint("real_da2_small_inference");screenshot("02-photo.png");
            // The first gesture follows the estimated surface and remains one independent stroke.
            float[][] wave=new float[37][2];
            for(int i=0;i<wave.length;i++){float x=-.65f+i*.035f;wave[i]=screen(x,-.15f+.17f*(float)Math.sin(i*.24f));}
            draw(wave);check(activity.scene.strokes.size()==1,"one gesture must produce one stroke");
            check(activity.scene.strokes.get(0).points.size()>12,"stroke missing sampled points");
            check(activity.scene.strokes.get(0).centered,"stroke pivot was not centered");
            main(()->clickText("悬空"));
            float[][] star=new float[11][2];
            for(int i=0;i<11;i++){double a=-Math.PI/2+i*Math.PI/5;float r=i%2==0?.27f:.12f;star[i]=screen(.52f+r*(float)Math.cos(a),.48f+r*(float)Math.sin(a));}
            draw(star);check(activity.scene.strokes.size()==2,"independent floating stroke missing");
            main(()->clickText("撤回"));check(activity.scene.strokes.size()==1,"undo failed");
            main(()->clickText("重做"));check(activity.scene.strokes.size()==2,"redo failed");checkpoint("draw_surface_float_undo_redo");
            main(()->clickText("⌖ 选中"));tap(star[0][0],star[0][1]);
            check(activity.scene.selected==1,"whole-stroke picking failed");
            check(findDescription(activity.getWindow().getDecorView(),"前后")!=null,"floating inspector missing");
            slide("前后",.78f);slide("旋转 X",.66f);slide("旋转 Y",.59f);slide("旋转 Z",.58f);
            Scene.Stroke selected=activity.scene.strokes.get(1);
            screenshot("03-stroke-inspector.png");
            check(selected.z>.5f&&Math.abs(selected.rx)>20&&Math.abs(selected.ry)>10&&Math.abs(selected.rz)>10,"inspector transforms failed: "+selected.z+", "+selected.rx+", "+selected.ry+", "+selected.rz);
            main(()->clickText("复制"));check(activity.scene.strokes.size()==3,"duplicate failed");
            main(()->clickText("删除"));check(activity.scene.strokes.size()==2,"delete failed");checkpoint("select_inspector_xyz_duplicate_delete");
            ByteArrayOutputStream output=new ByteArrayOutputStream();ProjectStore.write(output,activity.scene);
            Scene restored=ProjectStore.read(new ByteArrayInputStream(output.toByteArray()));
            check(restored.strokes.size()==2&&restored.depthWidth==activity.scene.depthWidth,"project roundtrip lost content");
            check(Math.abs(restored.strokes.get(1).rx-selected.rx)<1e-5f,"project roundtrip lost rotation");
            check(restored.strokes.get(0).points.size()==activity.scene.strokes.get(0).points.size(),"project roundtrip lost stroke samples");
            try {ProjectStore.read(new ByteArrayInputStream(new byte[]{1,2,3}));throw new AssertionError("invalid project accepted");}catch(IOException expected){}
            File folder=getTargetContext().getExternalFilesDir("checks");folder.mkdirs();
            try(FileOutputStream out=new FileOutputStream(new File(folder,"roundtrip.dsketch"))){out.write(output.toByteArray());}
            checkpoint("editable_project_roundtrip_and_reject_invalid");
            main(()->clickText("↻ 视角"));
            main(()->{activity.scene.pitch=0;activity.scene.yaw=0;activity.scene.roll=0;});
            drag(activity.canvas.getWidth()*.35f,activity.canvas.getHeight()*.55f,activity.canvas.getWidth()*.6f,activity.canvas.getHeight()*.65f);
            check(Math.abs(activity.scene.yaw)>15,"camera orbit failed");
            slide("视角 X",.64f);slide("视角 Y",.61f);slide("视角 Z",.54f);
            check(Math.abs(activity.scene.pitch)>5&&Math.abs(activity.scene.yaw)>5&&Math.abs(activity.scene.roll)>5,"independent camera axes failed");
            screenshot("04-orbit.png");checkpoint("camera_orbit_and_xyz");
            File png=new File(getTargetContext().getExternalFilesDir("checks"),"exported-view.png");
            main(()->activity.exportPNG(android.net.Uri.fromFile(png)));
            long exportDeadline=SystemClock.elapsedRealtime()+15000;
            while(activity.busy&&SystemClock.elapsedRealtime()<exportDeadline)SystemClock.sleep(100);
            check(png.length()>10000,"PNG export failed");
            Bitmap export=android.graphics.BitmapFactory.decodeFile(png.getAbsolutePath());
            check(export!=null&&export.getWidth()==activity.canvas.getWidth()&&export.getHeight()==activity.canvas.getHeight(),"PNG export has wrong dimensions");
            export.recycle();checkpoint("png_export");
            main(()->clickText("正视"));check(activity.scene.pitch==0&&activity.scene.yaw==0&&activity.scene.roll==0,"reset view failed");
            SystemClock.sleep(1200);check(new File(getTargetContext().getFilesDir(),"autosave.dsketch").length()>1000,"autosave missing");
            checkpoint("autosave");
            // Pinch updates the scene version without scheduling a drawing edit. Pausing must
            // still persist that view change even if an identical document was already saved.
            main(()->{synchronized(activity.scene){activity.scene.zoom=1.37f;activity.scene.changed();}activity.onPause();});
            boolean viewSaved=false;long saveDeadline=SystemClock.elapsedRealtime()+5000;
            while(!viewSaved&&SystemClock.elapsedRealtime()<saveDeadline){
                try(InputStream in=new FileInputStream(new File(activity.getFilesDir(),"autosave.dsketch"))){viewSaved=Math.abs(ProjectStore.read(in).zoom-1.37f)<.001f;}
                if(!viewSaved)SystemClock.sleep(100);
            }
            main(activity::onResume);check(viewSaved,"pause skipped a changed camera after prior autosave");checkpoint("pause_autosave_after_view_change");
            results.putString("result","PASS");finish(Activity.RESULT_OK,results);
        }catch(Throwable error){results.putString("result","FAIL");results.putString("error",android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,results);}
    }
    private void checkpoint(String name){results.putString(name,"PASS");Bundle update=new Bundle();update.putString("stream","PASS "+name+"\n");sendStatus(0,update);}
    private void main(Runnable action){runOnMainSync(action);waitForIdleSync();}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void clickText(String label){View view=findText(activity.getWindow().getDecorView(),label);check(view!=null,"control not found: "+label);view.performClick();}
    private View findText(View v,String label){if(v instanceof TextView&&((TextView)v).getText().toString().equals(label))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=findText(((ViewGroup)v).getChildAt(i),label);if(found!=null)return found;}return null;}
    private View findDescription(View v,String label){if(label.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=findDescription(((ViewGroup)v).getChildAt(i),label);if(found!=null)return found;}return null;}
    private float[] screen(float x,float y){AtomicReference<float[]> p=new AtomicReference<>();main(()->{synchronized(activity.scene){activity.canvas.renderer.updateCamera();p.set(activity.canvas.renderer.project(new float[]{x,y,0},null));}});return p.get();}
    private void event(View view,int action,float x,float y){main(()->{long time=SystemClock.uptimeMillis();if(action==MotionEvent.ACTION_DOWN)gestureTime=time;MotionEvent e=MotionEvent.obtain(gestureTime,time,action,x,y,0);view.dispatchTouchEvent(e);e.recycle();});}
    private void draw(float[][] points){event(activity.canvas,MotionEvent.ACTION_DOWN,points[0][0],points[0][1]);for(int i=1;i<points.length;i++)event(activity.canvas,MotionEvent.ACTION_MOVE,points[i][0],points[i][1]);event(activity.canvas,MotionEvent.ACTION_UP,points[points.length-1][0],points[points.length-1][1]);}
    private void tap(float x,float y){event(activity.canvas,MotionEvent.ACTION_DOWN,x,y);event(activity.canvas,MotionEvent.ACTION_UP,x,y);}
    private void drag(float x,float y,float ex,float ey){event(activity.canvas,MotionEvent.ACTION_DOWN,x,y);for(int i=1;i<=12;i++)event(activity.canvas,MotionEvent.ACTION_MOVE,x+(ex-x)*i/12,y+(ey-y)*i/12);event(activity.canvas,MotionEvent.ACTION_UP,ex,ey);}
    private void slide(String label,float fraction){
        View tab=findDescription(activity.getWindow().getDecorView(),"编辑 "+label);if(tab!=null)main(tab::performClick);
        View bar=findDescription(activity.getWindow().getDecorView(),label);check(bar instanceof SeekBar,"slider not found: "+label);
        SystemClock.sleep(100);waitForIdleSync();check(bar.getWidth()>0,"slider has no layout: "+label);
        float width=bar.getWidth()-bar.getPaddingLeft()-bar.getPaddingRight();
        float x=bar.getPaddingLeft()+fraction*width,y=bar.getHeight()/2f;
        float current=bar.getPaddingLeft()+((SeekBar)bar).getProgress()/(float)((SeekBar)bar).getMax()*width;
        event(bar,MotionEvent.ACTION_DOWN,current,y);
        // Native SeekBar preserves the grab offset when touching inside its thumb. Exercise a drag.
        event(bar,MotionEvent.ACTION_MOVE,Math.min(bar.getWidth()-bar.getPaddingRight(),x+activity.dp(30)),y);
        event(bar,MotionEvent.ACTION_MOVE,x,y);event(bar,MotionEvent.ACTION_UP,x,y);
    }
    private void screenshot(String name)throws Exception{SystemClock.sleep(300);Bitmap bitmap=getUiAutomation().takeScreenshot();check(bitmap!=null,"screenshot failed");File folder=getTargetContext().getExternalFilesDir("checks");folder.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(folder,name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();}
}
