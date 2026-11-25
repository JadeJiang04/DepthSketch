package studio.depthsketch;

import android.app.*;
import android.content.*;
import android.opengl.GLSurfaceView;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;

public final class WallpaperPreviewActivity extends Activity {
    private GLSurfaceView view;
    private Scene scene;
    private GravityMotion motion;
    private float basePitch,baseYaw;
    private boolean resumed,destroyed;
    private final ExecutorService loader=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().setStatusBarColor(MainActivity.BG);getWindow().setNavigationBarColor(MainActivity.BG);
        TextView loading=new TextView(this);loading.setText("正在准备重力壁纸…");loading.setTextColor(MainActivity.TEXT);loading.setGravity(Gravity.CENTER);loading.setBackgroundColor(MainActivity.BG);setContentView(loading);
        loader.execute(()->{try{Scene saved=WallpaperFiles.load(this,true);handler.post(()->{if(!destroyed)build(saved);});}catch(Exception e){handler.post(()->{if(!destroyed)new AlertDialog.Builder(this).setTitle("壁纸预览失败").setMessage(e.getMessage()).setPositiveButton("返回",(d,w)->finish()).show();});}});
    }
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private TextView label(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(MainActivity.TEXT);return t;}
    private void build(Scene saved){
        scene=saved;basePitch=scene.pitch;baseYaw=scene.yaw;SharedPreferences prefs=getSharedPreferences(WallpaperFiles.PREFS,0);
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(MainActivity.BG);setContentView(root);
        view=new GLSurfaceView(this);view.setEGLContextClientVersion(2);view.setEGLConfigChooser(8,8,8,8,16,0);view.setPreserveEGLContextOnPause(true);
        view.setRenderer(new SceneRenderer(scene));view.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);view.setContentDescription("重力动态壁纸预览");root.addView(view,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(18),dp(12),dp(18),dp(12));panel.setBackgroundColor(0xe8111512);
        ScrollView settings=new ScrollView(this);settings.addView(panel);FrameLayout.LayoutParams settingsLayout=new FrameLayout.LayoutParams(-1,Math.min(dp(360),getResources().getDisplayMetrics().heightPixels/2),Gravity.BOTTOM);root.addView(settings,settingsLayout);
        Button fold=new Button(this);fold.setText("收起设置");FrameLayout.LayoutParams foldLayout=new FrameLayout.LayoutParams(dp(104),dp(44),Gravity.TOP|Gravity.END);root.addView(fold,foldLayout);
        fold.setOnClickListener(v->{boolean hide=settings.getVisibility()==View.VISIBLE;settings.setVisibility(hide?View.GONE:View.VISIBLE);fold.setText(hide?"壁纸设置":"收起设置");});
        panel.addView(label("倾斜手机，查看深度视差",19));
        Switch enabled=new Switch(this);enabled.setText("重力视差");enabled.setTextColor(MainActivity.TEXT);enabled.setChecked(prefs.getBoolean("gravity",true));panel.addView(enabled);
        Switch fill=new Switch(this);fill.setText("填满屏幕（居中裁切）");fill.setTextColor(MainActivity.TEXT);fill.setChecked(scene.fillWallpaper);panel.addView(fill);
        panel.addView(label("重力幅度",12));SeekBar strength=new SeekBar(this);strength.setMax(120);strength.setProgress(Math.round(prefs.getFloat("strength",6)*10));strength.setContentDescription("重力幅度");panel.addView(strength);
        panel.addView(label("壁纸缩放",12));SeekBar zoom=new SeekBar(this);zoom.setMax(100);zoom.setProgress(Math.round((scene.wallpaperZoom-1)*100));zoom.setContentDescription("壁纸缩放");panel.addView(zoom);
        motion=new GravityMotion(this,(pitch,yaw)->{synchronized(scene){scene.pitch=basePitch+pitch;scene.yaw=baseYaw+yaw;}view.requestRender();});motion.strength=prefs.getFloat("strength",6);
        enabled.setOnCheckedChangeListener((b,value)->{prefs.edit().putBoolean("gravity",value).apply();if(value&&resumed)motion.start();else{motion.stop();motion.recenter();}});
        fill.setOnCheckedChangeListener((b,value)->{synchronized(scene){scene.fillWallpaper=value;}prefs.edit().putBoolean("fill",value).apply();view.requestRender();});
        strength.setOnSeekBarChangeListener(new MainActivity.SimpleSeek(){public void onProgressChanged(SeekBar b,int value,boolean user){if(user){motion.strength=value/10f;prefs.edit().putFloat("strength",motion.strength).apply();}}});
        zoom.setOnSeekBarChangeListener(new MainActivity.SimpleSeek(){public void onProgressChanged(SeekBar b,int value,boolean user){if(user){synchronized(scene){scene.wallpaperZoom=1+value/100f;}prefs.edit().putFloat("zoom",scene.wallpaperZoom).apply();view.requestRender();}}});
        Button center=new Button(this);center.setText("以当前握持姿势校准");center.setOnClickListener(v->motion.recenter());panel.addView(center);
        Button fit=new Button(this);fit.setText("恢复完整场景（缩放 1:1）");fit.setOnClickListener(v->{fill.setChecked(false);zoom.setProgress(0);synchronized(scene){scene.fillWallpaper=false;scene.wallpaperZoom=1;}prefs.edit().putBoolean("fill",false).putFloat("zoom",1).apply();view.requestRender();});panel.addView(fit);
        Button apply=new Button(this);apply.setText("设为动态壁纸");apply.setOnClickListener(v->{try{startActivityForResult(WallpaperFiles.previewIntent(this),90);}catch(ActivityNotFoundException e){new AlertDialog.Builder(this).setTitle("此系统未提供动态壁纸入口").setMessage("作品已保存在本机，可在系统壁纸设置中查看是否支持深绘动态壁纸。").setPositiveButton("知道了",null).show();}});panel.addView(apply);
        if(!motion.supported())panel.addView(label("未检测到倾斜传感器，可使用静止作品壁纸",12));
        if(resumed){view.onResume();if(enabled.isChecked())motion.start();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=90)return;android.util.Log.i("DepthWallpaper","SET_RESULT="+result);
        if(result==RESULT_OK)loader.execute(()->{try{WallpaperFiles.applyPreview(this);handler.post(()->{if(!destroyed)Toast.makeText(this,"重力壁纸已设置",Toast.LENGTH_SHORT).show();});}
            catch(Exception e){handler.post(()->{if(!destroyed)new AlertDialog.Builder(this).setTitle("壁纸作品更新失败").setMessage(e.getMessage()).setPositiveButton("知道了",null).show();});}});
    }
    @Override protected void onResume(){super.onResume();resumed=true;if(view!=null)view.onResume();if(motion!=null&&getSharedPreferences(WallpaperFiles.PREFS,0).getBoolean("gravity",true))motion.start();}
    @Override protected void onPause(){resumed=false;if(motion!=null)motion.stop();if(view!=null)view.onPause();super.onPause();}
    @Override protected void onDestroy(){destroyed=true;if(motion!=null)motion.stop();handler.removeCallbacksAndMessages(null);loader.shutdown();super.onDestroy();}
}
