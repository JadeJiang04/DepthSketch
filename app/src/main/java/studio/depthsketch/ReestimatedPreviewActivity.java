package studio.depthsketch;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.concurrent.*;

/** Independent experiment; retains the editable source in MainActivity and in its handoff. */
public final class ReestimatedPreviewActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    final Scene display=new Scene();volatile Scene source,result;
    private GLSurfaceView view;private GravityMotion motion;private boolean resumed,destroyed;
    private File sourceFile,resultFile;private TextView title;private Button originalButton,resultButton;
    private Switch gravity;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().setStatusBarColor(MainActivity.BG);getWindow().setNavigationBarColor(MainActivity.BG);
        TextView loading=new TextView(this);loading.setText("正在打开独立重估结果…");loading.setTextColor(MainActivity.TEXT);loading.setBackgroundColor(MainActivity.BG);setContentView(loading);
        worker.execute(()->{try{sourceFile=handoff("source_file");resultFile=handoff("result_file");try(InputStream in=new FileInputStream(sourceFile)){source=ProjectStore.read(in);}try(InputStream in=new FileInputStream(resultFile)){result=ProjectStore.read(in);}post(this::build);}catch(Exception e){post(()->error(e));}});
    }
    private File handoff(String key)throws IOException{String name=getIntent().getStringExtra(key);if(name==null||!name.matches("reestimate-[0-9]+-(source|result)\\.dsketch"))throw new IOException("重估结果无效");return new File(getCacheDir(),name);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setTextColor(MainActivity.TEXT);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(MainActivity.PANEL));b.setOnClickListener(v->action.run());return b;}
    private void build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(8),dp(12),dp(8));root.setBackgroundColor(MainActivity.BG);setContentView(root);
        title=new TextView(this);title.setTextColor(MainActivity.TEXT);title.setTextSize(19);root.addView(title);
        TextView hint=new TextView(this);hint.setText("独立预览：原分层作品保留。重估结果是一张图片，无法再单独选中人物。");hint.setTextColor(MainActivity.MUTED);hint.setTextSize(12);root.addView(hint);
        view=new GLSurfaceView(this);view.setEGLContextClientVersion(2);view.setEGLConfigChooser(8,8,8,8,16,0);view.setPreserveEGLContextOnPause(true);view.setContentDescription("合成重估预览");view.setRenderer(new SceneRenderer(display));view.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);root.addView(view,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout tabs=new LinearLayout(this);originalButton=button("原分层",()->select(false));resultButton=button("重估结果",()->select(true));tabs.addView(originalButton,new LinearLayout.LayoutParams(0,dp(46),1));tabs.addView(resultButton,new LinearLayout.LayoutParams(0,dp(46),1));root.addView(tabs);
        motion=new GravityMotion(this,(pitch,yaw)->{synchronized(display){display.pitch=pitch;display.yaw=yaw;}view.requestRender();});
        gravity=new Switch(this);gravity.setText("倾斜手机比较视差");gravity.setTextColor(MainActivity.TEXT);gravity.setOnCheckedChangeListener((b,on)->{if(on&&resumed)motion.start();else{motion.stop();synchronized(display){display.pitch=display.yaw=0;}view.requestRender();}});root.addView(gravity);
        root.addView(button("导出重估项目",()->{Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"合成重估.dsketch");startActivityForResult(intent,10);}));
        root.addView(button("预览为动态壁纸",()->worker.execute(()->{try{WallpaperFiles.publish(this,result);post(()->startActivity(new Intent(this,WallpaperPreviewActivity.class)));}catch(Exception e){post(()->error(e));}})));
        root.addView(button("返回原作品",this::finish));select(true);if(resumed)view.onResume();
    }
    private void select(boolean reestimated){
        Scene selected=reestimated?result:source;
        synchronized(display){display.setImage(selected.image,selected.depth,selected.depthWidth,selected.depthHeight);display.relief=selected.relief;for(Scene.Stroke s:selected.strokes)display.strokes.add(s.copy());for(Scene.Cutout c:selected.cutouts)display.cutouts.add(c.copy());display.wallpaper=true;display.fillWallpaper=false;display.wallpaperZoom=1;}
        title.setText(reestimated?"合成后重新估深 · 实验":"原分层深度 · 对照");resultButton.setTextColor(reestimated?MainActivity.BG:MainActivity.TEXT);originalButton.setTextColor(reestimated?MainActivity.TEXT:MainActivity.BG);resultButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(reestimated?MainActivity.ACCENT:MainActivity.PANEL));originalButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(reestimated?MainActivity.PANEL:MainActivity.ACCENT));view.requestRender();
    }
    @Override protected void onActivityResult(int request,int code,Intent data){super.onActivityResult(request,code,data);if(request==10&&code==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();worker.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(uri)){ProjectStore.write(out,result);post(()->Toast.makeText(this,"已导出独立重估项目",Toast.LENGTH_SHORT).show());}catch(Exception e){post(()->error(e));}});}}
    private void error(Exception e){new AlertDialog.Builder(this).setTitle("重估预览未完成").setMessage(e.getMessage()).setPositiveButton("返回",(d,w)->finish()).show();}
    private void post(Runnable r){handler.post(()->{if(!destroyed)r.run();});}
    @Override protected void onResume(){super.onResume();resumed=true;if(view!=null)view.onResume();if(motion!=null&&gravity.isChecked())motion.start();}
    @Override protected void onPause(){resumed=false;if(motion!=null)motion.stop();if(view!=null)view.onPause();super.onPause();}
    @Override protected void onDestroy(){destroyed=true;handler.removeCallbacksAndMessages(null);if(isFinishing())worker.execute(()->{if(sourceFile!=null)sourceFile.delete();if(resultFile!=null)resultFile.delete();});worker.shutdown();super.onDestroy();}
}
