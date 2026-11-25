package studio.depthsketch;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class CutoutActivity extends Activity {
    MaskPickerView picker;
    volatile boolean busy=true;
    private boolean positive=true;
    private volatile boolean destroyed;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private ObjectSegmenter segmenter;
    private DepthEstimator estimator;
    private DepthEstimator.Result depth;
    private float[] mask;
    private TextView status;
    private Button add,include,exclude;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().setStatusBarColor(MainActivity.BG);getWindow().setNavigationBarColor(MainActivity.BG);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(20,12,20,20);root.setBackgroundColor(MainActivity.BG);setContentView(root);
        TextView title=label("点选新图中的对象",23);root.addView(title);
        status=label("正在读取照片…",13);status.setPadding(0,12,0,12);root.addView(status);
        picker=new MaskPickerView(this);root.addView(picker,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=new LinearLayout(this);include=button("选主体",()->{positive=true;buttons();});exclude=button("排除背景",()->{positive=false;buttons();});
        actions.addView(include,new LinearLayout.LayoutParams(0,50*dp(),1));actions.addView(exclude,new LinearLayout.LayoutParams(0,50*dp(),1));
        actions.addView(button("撤回点",()->{if(busy||picker.prompts.isEmpty())return;picker.prompts.remove(picker.prompts.size()-1);refine();}),new LinearLayout.LayoutParams(0,50*dp(),1));root.addView(actions);
        add=button("加入画布",this::confirm);root.addView(add,new LinearLayout.LayoutParams(-1,52*dp()));add.setEnabled(false);
        root.addView(button("取消",this::finish),new LinearLayout.LayoutParams(-1,44*dp()));buttons();
        picker.listener=(x,y)->{if(busy||picker.prompts.size()>=32)return;if(picker.prompts.isEmpty()&&!positive){status.setText("先在主体内部点一下，再排除背景");return;}picker.prompts.add(new float[]{x,y,positive?1:0});refine();};
        segmenter=new ObjectSegmenter(this);estimator=new DepthEstimator(this);
        worker.execute(()->{
            try{
                Bitmap bitmap=ImageFiles.decode(this,getIntent().getData());if(bitmap==null)throw new IOException("无法读取新图片");
                post(()->{picker.image=bitmap;picker.invalidate();status.setText("正在准备点选抠图模型…");});
                // Depth is needed only for an accepted object. Let the user refine the mask first.
                segmenter.prepare(bitmap);
                post(()->{busy=false;picker.setEnabled(true);status.setText("点击主体内部 · 可补点，也可排除背景");});
            }catch(Throwable e){post(()->{busy=false;error(e);});}
        });
    }
    private int dp(){return Math.max(1,Math.round(getResources().getDisplayMetrics().density));}
    private TextView label(String text,int size){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(MainActivity.TEXT);return v;}
    private Button button(String text,Runnable action){Button b=new Button(this);b.setText(text);b.setTextColor(MainActivity.TEXT);b.setOnClickListener(v->action.run());return b;}
    private void buttons(){include.setTextColor(positive?MainActivity.ACCENT:MainActivity.TEXT);exclude.setTextColor(positive?MainActivity.TEXT:0xffff8181);}
    private void post(Runnable action){handler.post(()->{if(!destroyed)action.run();});}
    void refine(){
        if(busy)return;
        if(picker.prompts.isEmpty()){mask=null;picker.overlay=null;picker.invalidate();add.setEnabled(false);status.setText("点击主体内部开始抠图");return;}
        busy=true;picker.setEnabled(false);add.setEnabled(false);status.setText("正在细化对象轮廓…");picker.invalidate();
        List<float[]> points=new ArrayList<>();for(float[] p:picker.prompts)points.add(p.clone());
        worker.execute(()->{
            try{
                long started=SystemClock.elapsedRealtime();
                float[] result=segmenter.segment(points);int w=picker.image.getWidth(),h=picker.image.getHeight();int[] rgba=new int[w*h];int foreground=0;
                android.util.Log.i("DepthSketchTiming","point_segment ms="+(SystemClock.elapsedRealtime()-started)+" removedPixels="+segmenter.lastCleanup.removedPixels);
                for(int i=0;i<rgba.length;i++)if(result[i]>0){rgba[i]=0x669be76e;foreground++;}
                Bitmap overlay=Bitmap.createBitmap(rgba,w,h,Bitmap.Config.ARGB_8888);int area=foreground;
                post(()->{mask=result;picker.overlay=overlay;picker.invalidate();busy=false;picker.setEnabled(true);add.setEnabled(area>=30);status.setText("已选中对象 · 补点完善轮廓，然后加入画布");});
            }catch(Throwable e){post(()->{busy=false;picker.setEnabled(true);error(e);});}
        });
    }
    void confirm(){
        if(busy||mask==null)return;busy=true;add.setEnabled(false);status.setText("正在估计对象深度…");
        worker.execute(()->{
            try{
                if(depth==null){try{depth=estimator.estimate(picker.image);}finally{estimator.close();}}
                post(()->status.setText("正在裁剪图像和对应深度…"));
                Scene.Cutout cutout=ObjectSegmenter.crop(picker.image,mask,depth);
                Scene transfer=ProjectStore.cutoutTransfer(cutout);
                File file=new File(getCacheDir(),"cutout-"+System.nanoTime()+".dsketch");ProjectStore.write(new FileOutputStream(file),transfer);
                post(()->{setResult(RESULT_OK,new Intent().putExtra("cutout_file",file.getName()));finish();});
            }catch(Throwable e){post(()->{busy=false;add.setEnabled(true);error(e);});}
        });
    }
    private void error(Throwable e){android.util.Log.e("DepthSketch","Cutout failed",e);new AlertDialog.Builder(this).setTitle("抠图未完成").setMessage(e.getMessage()).setPositiveButton("知道了",null).show();}
    @Override protected void onDestroy(){destroyed=true;handler.removeCallbacksAndMessages(null);worker.execute(()->{segmenter.close();estimator.close();});worker.shutdown();super.onDestroy();}
}
