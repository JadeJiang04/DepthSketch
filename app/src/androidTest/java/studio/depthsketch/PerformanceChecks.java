package studio.depthsketch;

import android.app.Instrumentation;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import org.json.*;
import java.io.*;
import java.util.Arrays;
import java.util.concurrent.*;

/** Controlled render workload, not a model speed test or a phone FPS claim. */
final class PerformanceChecks {
    static Bundle run(Instrumentation test,MainActivity activity) throws Exception {
        Scene loaded=new Scene();
        try(InputStream in=test.getTargetContext().getAssets().open("sample.jpg")) {
            float[] depth=new float[64*64];
            for(int i=0;i<depth.length;i++)depth[i]=.2f+.6f*(i/64)/63f;
            loaded.setImage(BitmapFactory.decodeStream(in),depth,64,64);
        }
        for(int j=0;j<30;j++){
            Scene.Stroke s=new Scene.Stroke();s.color=0xffb9ee8a;s.radius=.006f;
            for(int i=0;i<160;i++)s.points.add(new float[]{-1.1f+i*2.2f/159,(j-15)*.045f+.04f*(float)Math.sin(i*.2),.48f});
            s.center();loaded.strokes.add(s);
        }
        test.runOnMainSync(()->activity.applyProject(loaded));test.waitForIdleSync();
        SystemClock.sleep(400);
        long[] samples=new long[50];long warmedUploads=-1;
        for(int i=-5;i<samples.length;i++){
            int frame=i;CountDownLatch done=new CountDownLatch(1);
            test.runOnMainSync(()->{synchronized(activity.scene){activity.scene.yaw=frame*.7f;activity.scene.selected=frame%2==0?2:-1;}});
            activity.canvas.queueEvent(()->{
                long start=System.nanoTime();activity.canvas.renderer.onDrawFrame(null);GLES20.glFinish();
                if(frame>=0)samples[frame]=System.nanoTime()-start;done.countDown();
            });
            if(!done.await(15,TimeUnit.SECONDS))throw new AssertionError("render timeout");
            if(i==-1)warmedUploads=uploads(activity);
        }
        long orbitUploads=warmedUploads<0?-1:uploads(activity)-warmedUploads;
        if(orbitUploads>0)throw new AssertionError("orbit/selection rebuilt stroke geometry");
        Arrays.sort(samples);
        // One continuous freehand gesture while 30 completed strokes remain visible.
        // Each move includes eight historical/current samples, like Android's batched pen input.
        test.runOnMainSync(()->activity.canvas.setMode(SketchView.Mode.DRAW));
        long down=SystemClock.uptimeMillis();
        test.runOnMainSync(()->pointer(activity,MotionEvent.ACTION_DOWN,down,0,false));
        long[] inputTimes=new long[40],drawingTimes=new long[40];
        for(int i=0;i<40;i++){
            int frame=i;test.runOnMainSync(()->{
                long start=System.nanoTime();pointer(activity,MotionEvent.ACTION_MOVE,down,frame*8+1,true);inputTimes[frame]=System.nanoTime()-start;
            });
            CountDownLatch done=new CountDownLatch(1);
            activity.canvas.queueEvent(()->{long start=System.nanoTime();activity.canvas.renderer.onDrawFrame(null);GLES20.glFinish();drawingTimes[frame]=System.nanoTime()-start;done.countDown();});
            if(!done.await(15,TimeUnit.SECONDS))throw new AssertionError("live draw timeout");
        }
        test.runOnMainSync(()->pointer(activity,MotionEvent.ACTION_UP,down,320,false));
        test.waitForIdleSync();
        if(activity.scene.strokes.size()!=31||activity.scene.strokes.get(30).points.size()<150)throw new AssertionError("continuous freehand samples were dropped");
        Arrays.sort(inputTimes);Arrays.sort(drawingTimes);
        // A stalled output sink must not prevent a UI edit from acquiring the scene lock.
        CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1),uiEdit=new CountDownLatch(1);
        AtomicError failure=new AtomicError();
        Thread writer=new Thread(()->{
            try{ProjectStore.write(new OutputStream(){
                boolean first=true;
                public void write(int b)throws IOException{gate();}
                public void write(byte[] b,int off,int count)throws IOException{gate();}
                private void gate()throws IOException{if(first){first=false;blocked.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IOException(e);}}}
            },activity.scene);}catch(Exception e){failure.error=e;}finally{finished.countDown();}
        });
        writer.start();if(!blocked.await(5,TimeUnit.SECONDS))throw new AssertionError("save did not reach output");
        long probe=SystemClock.elapsedRealtime();
        new Handler(Looper.getMainLooper()).post(()->{synchronized(activity.scene){activity.scene.zoom=1.01f;}uiEdit.countDown();});
        boolean responsive=uiEdit.await(200,TimeUnit.MILLISECONDS);long probeMs=SystemClock.elapsedRealtime()-probe;
        release.countDown();if(!finished.await(10,TimeUnit.SECONDS))throw new AssertionError("save did not finish");
        if(failure.error!=null)throw failure.error;
        test.waitForIdleSync();
        JSONObject report=new JSONObject();report.put("strokes",30);report.put("points_per_stroke",160);
        report.put("frames",samples.length);report.put("median_render_ms",samples[25]/1e6);
        report.put("p95_render_ms",samples[47]/1e6);report.put("max_render_ms",samples[49]/1e6);
        report.put("orbit_geometry_uploads",orbitUploads);
        report.put("live_input_samples",activity.scene.strokes.get(30).points.size());
        report.put("live_input_p95_ms",inputTimes[37]/1e6);report.put("live_render_median_ms",drawingTimes[20]/1e6);
        report.put("live_render_p95_ms",drawingTimes[37]/1e6);
        report.put("ui_edit_during_stalled_save_within_200ms",responsive);report.put("ui_probe_wait_ms",probeMs);
        File file=new File(test.getTargetContext().getExternalFilesDir("checks"),"performance.json");
        try(FileOutputStream out=new FileOutputStream(file)){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        Bundle result=new Bundle();result.putString("performance",report.toString());result.putString("result","PASS");return result;
    }
    private static final class AtomicError {volatile Exception error;}
    private static long uploads(MainActivity activity){
        try{
            Object gpu=activity.canvas.renderer.getClass().getDeclaredField("gpu").get(activity.canvas.renderer);
            return gpu.getClass().getDeclaredField("geometryUploads").getLong(gpu);
        }catch(ReflectiveOperationException ignored){return -1;}
    }
    private static void pointer(MainActivity activity,int action,long down,int sample,boolean batch){
        float w=activity.canvas.getWidth(),h=activity.canvas.getHeight();
        float x=w*(.15f+.7f*sample/320),y=h*(.5f+.07f*(float)Math.sin(sample*.08));
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);
        if(batch)for(int i=1;i<8;i++){
            int n=sample+i;event.addBatch(SystemClock.uptimeMillis(),w*(.15f+.7f*n/320),h*(.5f+.07f*(float)Math.sin(n*.08)),1,1,0);
        }
        activity.canvas.dispatchTouchEvent(event);event.recycle();
    }
}
