package studio.depthsketch;

import android.app.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Actual two-pointer gestures, compact editing, persistence and non-destructive composite re-estimation. */
final class CanvasInteractionChecks {
    private final Instrumentation test;private final MainActivity main;private final Bundle result=new Bundle();private long down;
    CanvasInteractionChecks(Instrumentation test,MainActivity main){this.test=test;this.main=main;}
    Bundle run()throws Exception {
        Scene fixture;File path=new File(main.getExternalFilesDir("latency-input"),"interaction-fixture.dsketch");
        if(path.exists())try(InputStream in=new FileInputStream(path)){fixture=ProjectStore.read(in);}
        else{fixture=new Scene();Bitmap image=Bitmap.createBitmap(240,320,Bitmap.Config.ARGB_8888);image.eraseColor(0xff7392aa);fixture.setImage(image,new float[]{.2f,.4f,.7f,.8f},2,2);Scene.Cutout c=new Scene.Cutout();c.width=64;c.height=96;c.image=Bitmap.createBitmap(c.width,c.height,Bitmap.Config.ARGB_8888);c.image.eraseColor(0xffd5cf9f);c.depth=new float[c.width*c.height];Arrays.fill(c.depth,.5f);fixture.cutouts.add(c);}
        fixture.home();fixture.cutouts.get(0).x=fixture.cutouts.get(0).y=0;fixture.cutouts.get(0).scale=.4f;fixture.cutouts.get(0).z=.8f;
        Scene initial=fixture;ui(()->{main.applyProject(initial);main.canvas.setMode(SketchView.Mode.DRAW);});SystemClock.sleep(250);
        float w=main.canvas.getWidth(),h=main.canvas.getHeight();float[] before=project(0,0,0);int strokes=main.scene.strokes.size();
        twoFinger(w*.25f,h*.42f,w*.65f,h*.42f,72,35,1);float[] moved=project(0,0,0);
        check(Math.abs(moved[0]-before[0]-72)<2&&Math.abs(moved[1]-before[1]-35)<2,"two-finger pan displacement");check(main.scene.strokes.size()==strokes,"pan painted a provisional stroke");pass("two_finger_pan_without_paint");
        float fx=w*.5f,fy=h*.5f;AtomicReference<float[]> anchor=new AtomicReference<>();ui(()->anchor.set(main.canvas.renderer.screenPlane(fx,fy,0)));
        // Meet the device's physical 27 mm minimum scaling span, not just its dp touch slop.
        float oldZoom=main.scene.zoom;twoFinger(w*.15f,fy,w*.85f,fy,0,0,1.4f);float[] anchored=project(anchor.get()[0],anchor.get()[1],0);
        check(main.scene.zoom>oldZoom*1.15f,"pinch did not zoom: "+oldZoom+" -> "+main.scene.zoom);check(Math.hypot(anchored[0]-fx,anchored[1]-fy)<3,"pinch focus drift");pass("pinch_focus_remains_under_fingers");
        float oldPanX=main.scene.panX,oldPanY=main.scene.panY,zoom=main.scene.zoom;ui(()->findText(main.getWindow().getDecorView(),"✎ 手绘").performClick());check(main.scene.panX==oldPanX&&main.scene.panY==oldPanY&&main.scene.zoom==zoom,"draw mode reset navigation");
        float[] p=project(-.2f,.15f,0),q=project(.2f,.15f,0);event(main.canvas,MotionEvent.ACTION_DOWN,p[0],p[1]);event(main.canvas,MotionEvent.ACTION_MOVE,(p[0]+q[0])*.5f,p[1]);event(main.canvas,MotionEvent.ACTION_UP,q[0],q[1]);
        Scene.Stroke stroke=main.scene.strokes.get(main.scene.strokes.size()-1);check(Math.abs(stroke.x+stroke.points.get(0)[0]+.2f)<.01,"draw after pan has wrong world X");check(Math.abs(stroke.y+stroke.points.get(0)[1]-.15f)<.01,"draw after pan has wrong world Y");pass("drawing_coordinates_after_pan_zoom");
        ui(()->main.onCutoutSelected(0));SystemClock.sleep(100);View panel=findDescription(main.getWindow().getDecorView(),"对象编辑框");check(panel.getHeight()<=main.dp(170),"inspector still too tall: "+panel.getHeight());result.putInt("inspector_expanded_px",panel.getHeight());
        ui(()->findDescription(main.getWindow().getDecorView(),"收起编辑框").performClick());SystemClock.sleep(100);check(panel.getHeight()<=main.dp(55),"collapsed inspector too tall");result.putInt("inspector_collapsed_px",panel.getHeight());ui(()->findDescription(main.getWindow().getDecorView(),"展开编辑框").performClick());
        ui(()->findDescription(main.getWindow().getDecorView(),"编辑 边缘柔化").performClick());SystemClock.sleep(80);View slider=findDescription(main.getWindow().getDecorView(),"边缘柔化");check(slider instanceof SeekBar,"edge slider missing");event(slider,MotionEvent.ACTION_DOWN,slider.getWidth()*.3f,slider.getHeight()/2f);event(slider,MotionEvent.ACTION_MOVE,slider.getWidth()*.4f,slider.getHeight()/2f);event(slider,MotionEvent.ACTION_UP,slider.getWidth()*.4f,slider.getHeight()/2f);check(main.scene.cutouts.get(0).feather>2,"edge control did not update");
        screenshot("11-compact-inspector.png");pass("compact_inspector_fold_tabs_feather");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();ProjectStore.write(bytes,main.scene);Scene restored=ProjectStore.read(new ByteArrayInputStream(bytes.toByteArray()));check(restored.panX==main.scene.panX&&restored.panY==main.scene.panY&&restored.cutouts.get(0).feather==main.scene.cutouts.get(0).feather,"pan/feather roundtrip");pass("pan_feather_project_roundtrip");
        int originalCuts=main.scene.cutouts.size(),originalStrokes=main.scene.strokes.size();Bitmap originalImage=main.scene.image;Instrumentation.ActivityMonitor monitor=test.addMonitor(ReestimatedPreviewActivity.class.getName(),null,false);
        ui(main::reestimateComposition);ReestimatedPreviewActivity preview=(ReestimatedPreviewActivity)test.waitForMonitorWithTimeout(monitor,30000);test.removeMonitor(monitor);check(preview!=null,"composite re-estimation preview missing");
        long deadline=SystemClock.elapsedRealtime()+15000;while((preview.result==null||findDescription(preview.getWindow().getDecorView(),"合成重估预览")==null)&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100);test.waitForIdleSync();check(preview.result!=null,"re-estimation did not load");
        check(preview.result.cutouts.isEmpty()&&preview.result.strokes.isEmpty(),"composite result was not flattened");check(preview.result.image.getWidth()==originalImage.getWidth()&&preview.result.image.getHeight()==originalImage.getHeight(),"composite resolution changed");
        check(main.scene.image==originalImage&&main.scene.cutouts.size()==originalCuts&&main.scene.strokes.size()==originalStrokes,"re-estimation changed source");check(preview.source.cutouts.size()==originalCuts,"comparison lost layers");
        ui(()->findText(preview.getWindow().getDecorView(),"原分层").performClick());check(preview.display.cutouts.size()==originalCuts,"original comparison did not switch");ui(()->findText(preview.getWindow().getDecorView(),"重估结果").performClick());check(preview.display.cutouts.isEmpty(),"result comparison did not switch");screenshot("12-composite-reestimated.png");ui(preview::finish);pass("composite_real_depth_independent_preview");
        ui(()->{main.scene.home();main.canvas.requestFrame();});result.putString("result","PASS");return result;
    }
    private float[] project(float x,float y,float z){AtomicReference<float[]> p=new AtomicReference<>();ui(()->{main.canvas.renderer.updateCamera();p.set(main.canvas.renderer.project(new float[]{x,y,z},null));});return p.get();}
    private void twoFinger(float ax,float ay,float bx,float by,float dx,float dy,float scale){
        pointers(MotionEvent.ACTION_DOWN,new float[]{ax},new float[]{ay});pointers(MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),new float[]{ax,bx},new float[]{ay,by});
        float cx=(ax+bx)*.5f,cy=(ay+by)*.5f;
        for(int i=1;i<=10;i++){SystemClock.sleep(30);float t=i/10f,s=1+(scale-1)*t;pointers(MotionEvent.ACTION_MOVE,new float[]{cx+(ax-cx)*s+dx*t,cx+(bx-cx)*s+dx*t},new float[]{cy+(ay-cy)*s+dy*t,cy+(by-cy)*s+dy*t});}
        float x1=cx+(ax-cx)*scale+dx,y1=cy+(ay-cy)*scale+dy,x2=cx+(bx-cx)*scale+dx,y2=cy+(by-cy)*scale+dy;
        pointers(MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),new float[]{x1,x2},new float[]{y1,y2});pointers(MotionEvent.ACTION_UP,new float[]{x1},new float[]{y1});
    }
    private void pointers(int action,float[] x,float[] y){ui(()->{long now=SystemClock.uptimeMillis();if(action==MotionEvent.ACTION_DOWN)down=now;MotionEvent.PointerProperties[] props=new MotionEvent.PointerProperties[x.length];MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[x.length];for(int i=0;i<x.length;i++){props[i]=new MotionEvent.PointerProperties();props[i].id=i;props[i].toolType=MotionEvent.TOOL_TYPE_FINGER;coords[i]=new MotionEvent.PointerCoords();coords[i].x=x[i];coords[i].y=y[i];coords[i].pressure=coords[i].size=1;}MotionEvent e=MotionEvent.obtain(down,now,action,x.length,props,coords,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);main.canvas.dispatchTouchEvent(e);e.recycle();});}
    private void event(View view,int action,float x,float y){ui(()->{long now=SystemClock.uptimeMillis();if(action==0)down=now;MotionEvent e=MotionEvent.obtain(down,now,action,x,y,0);view.dispatchTouchEvent(e);e.recycle();});}
    private void ui(Runnable action){test.runOnMainSync(action);test.waitForIdleSync();}
    private void pass(String value){result.putString(value,"PASS");Bundle b=new Bundle();b.putString("stream","PASS "+value+"\n");test.sendStatus(0,b);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static View findText(View v,String text){if(v instanceof TextView&&text.equals(((TextView)v).getText().toString()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=findText(((ViewGroup)v).getChildAt(i),text);if(found!=null)return found;}return null;}
    private static View findDescription(View v,String text){if(text.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=findDescription(((ViewGroup)v).getChildAt(i),text);if(found!=null)return found;}return null;}
    private void screenshot(String name)throws Exception{SystemClock.sleep(150);Bitmap image=test.getUiAutomation().takeScreenshot();File folder=main.getExternalFilesDir("checks");folder.mkdirs();try(OutputStream out=new FileOutputStream(new File(folder,name))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
}
