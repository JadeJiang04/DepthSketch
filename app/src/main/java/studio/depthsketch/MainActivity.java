package studio.depthsketch;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity implements SketchView.Listener {
    static final int BG=0xff111512,PANEL=0xff1b231e,TEXT=0xffeef2e9,MUTED=0xff9ca99e,ACCENT=0xffb9ee8a;
    private static final int PICK_IMAGE=10,OPEN_PROJECT=11,SAVE_PROJECT=12,SAVE_PNG=13;
    private static final int PICK_CUTOUT=14,CUTOUT_DONE=15;
    private static final int PICK_MATERIAL=16;
    final Scene scene=new Scene();
    SketchView canvas;
    private FrameLayout stage;
    private LinearLayout inspector,welcome,cameraPanel;
    private boolean inspectorCollapsed;
    private TextView status,tip,count;
    private Button drawButton,selectButton,orbitButton,surfaceButton,spaceButton,photoButton;
    private LinearLayout palette;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private DepthEstimator estimator;
    boolean busy;
    private boolean destroyed,restoring;
    private final ArrayList<Scene> undo=new ArrayList<>(),redo=new ArrayList<>();
    private volatile long saveGeneration;
    private volatile long savedGeneration=-1;
    private long savedRevision=Long.MIN_VALUE;
    private final java.util.concurrent.atomic.AtomicBoolean saveQueued=new java.util.concurrent.atomic.AtomicBoolean();
    private final Runnable autosave=this::queueSave;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        estimator=new DepthEstimator(this); buildUI();
        File saved=new File(getFilesDir(),"autosave.dsketch");
        if (saved.exists()) {
            setBusy(true,"正在恢复上次的画布…");
            worker.execute(()-> {
                try (InputStream in=new FileInputStream(saved)) {
                    Scene loaded=ProjectStore.read(in);
                    ui(()-> { applyProject(loaded);setBusy(false,"已恢复上次的画布"); });
                } catch (Exception e) { ui(()->{setBusy(false,"就绪 · 照片与笔画仅保存在本机");error("上次项目未能恢复",e);}); }
            });
        } else status.setText("离线可用 · 无需账号");
    }
    int dp(float v) { return Math.round(v*getResources().getDisplayMetrics().density); }
    private GradientDrawable background(int color,float radius) {
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
    }
    private TextView text(String value,int size,int color) {
        TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setGravity(Gravity.CENTER_VERTICAL);return v;
    }
    private Button button(String label,Runnable action) {
        Button b=new Button(this);b.setText(label);b.setTextSize(13);b.setTextColor(TEXT);b.setAllCaps(false);
        b.setMinHeight(0);b.setMinimumHeight(0);b.setMinWidth(0);b.setMinimumWidth(0);
        b.setPadding(dp(10),dp(4),dp(10),dp(4));b.setBackground(background(PANEL,12));b.setOnClickListener(v->action.run());return b;
    }
    private LinearLayout row() { LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l; }
    private void gap(LinearLayout layout,int amount) { View v=new View(this);layout.addView(v,new LinearLayout.LayoutParams(dp(amount),dp(amount))); }
    private void buildUI() {
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);setContentView(root);
        LinearLayout header=row();header.setPadding(dp(18),dp(6),dp(16),dp(6));
        LinearLayout brand=new LinearLayout(this);brand.setOrientation(LinearLayout.VERTICAL);
        TextView title=text("深绘",24,TEXT);title.setTypeface(null,Typeface.BOLD);brand.addView(title);
        TextView subtitle=text("DEPTH / SKETCH",10,MUTED);subtitle.setLetterSpacing(.16f);brand.addView(subtitle);
        header.addView(brand,new LinearLayout.LayoutParams(0,dp(61),1));
        header.addView(button("＋ 图片",this::pickImage),new LinearLayout.LayoutParams(dp(78),dp(40)));gap(header,8);
        header.addView(button("⋯",this::showMenu),new LinearLayout.LayoutParams(dp(42),dp(40)));
        root.addView(header,new LinearLayout.LayoutParams(-1,dp(75)));

        LinearLayout info=row();info.setPadding(dp(18),0,dp(18),dp(8));
        status=text("就绪",11,MUTED);info.addView(status,new LinearLayout.LayoutParams(0,dp(24),1));
        count=text("0 笔",11,ACCENT);info.addView(count);root.addView(info);

        stage=new FrameLayout(this);root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        canvas=new SketchView(this,scene);canvas.listener=this;stage.addView(canvas,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout rail=row();rail.setGravity(Gravity.END);
        rail.addView(button("正视",this::home),new LinearLayout.LayoutParams(dp(52),dp(34)));gap(rail,6);
        photoButton=button("底图",()-> { if (!ready()) return; synchronized(scene){scene.showPhoto=!scene.showPhoto;}photoButton.setText(scene.showPhoto?"底图":"纯笔画");canvas.requestFrame();scheduleSave(); });
        rail.addView(photoButton,new LinearLayout.LayoutParams(dp(64),dp(34)));
        gap(rail,6);rail.addView(button("合成重估",this::reestimateComposition),new LinearLayout.LayoutParams(dp(78),dp(34)));
        FrameLayout.LayoutParams rp=new FrameLayout.LayoutParams(-2,dp(34),Gravity.TOP|Gravity.RIGHT);rp.setMargins(0,dp(12),dp(14),0);stage.addView(rail,rp);
        TextView badge=text("3D  /  CANVAS",10,0xff6c8172);badge.setLetterSpacing(.1f);
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-2,dp(30),Gravity.TOP|Gravity.LEFT);bp.setMargins(dp(18),dp(14),0,0);stage.addView(badge,bp);

        welcome=new LinearLayout(this);welcome.setOrientation(LinearLayout.VERTICAL);welcome.setGravity(Gravity.CENTER);welcome.setPadding(dp(32),dp(24),dp(32),dp(24));
        TextView motif=text("〰",72,ACCENT);motif.setGravity(Gravity.CENTER);welcome.addView(motif);
        TextView lead=text("把一笔，放进照片里",23,TEXT);lead.setGravity(Gravity.CENTER);lead.setTypeface(null,Typeface.BOLD);welcome.addView(lead);
        TextView details=text("选一张照片，在表面描绘\n也可以让笔画悬浮在空间里",14,MUTED);details.setGravity(Gravity.CENTER);details.setLineSpacing(dp(6),1);details.setPadding(0,dp(16),0,dp(22));welcome.addView(details);
        Button importButton=button("选择照片",this::pickImage);importButton.setTextColor(BG);importButton.setBackground(background(ACCENT,14));welcome.addView(importButton,new LinearLayout.LayoutParams(dp(174),dp(46)));
        Button sample=button("先试试示例 →",this::loadSample);sample.setBackgroundColor(Color.TRANSPARENT);sample.setTextColor(MUTED);welcome.addView(sample,new LinearLayout.LayoutParams(dp(174),dp(46)));
        stage.addView(welcome,new FrameLayout.LayoutParams(-1,-1));
        inspector=new LinearLayout(this);inspector.setOrientation(LinearLayout.VERTICAL);inspector.setPadding(dp(10),dp(6),dp(10),dp(8));inspector.setBackground(background(0xf5222e25,18));inspector.setElevation(dp(14));inspector.setVisibility(View.GONE);inspector.setContentDescription("对象编辑框");
        FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(dp(260),-2,Gravity.BOTTOM|Gravity.RIGHT);ip.setMargins(dp(12),dp(12),dp(12),dp(18));stage.addView(inspector,ip);

        tip=text("选择照片开始 · 一次落笔，一个对象",11,MUTED);tip.setGravity(Gravity.CENTER);tip.setPadding(dp(10),dp(8),dp(10),dp(8));root.addView(tip,new LinearLayout.LayoutParams(-1,dp(34)));
        LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);controls.setPadding(dp(14),dp(10),dp(14),dp(14));controls.setBackground(background(0xff171e19,20));root.addView(controls);
        LinearLayout modes=row();
        drawButton=button("✎ 手绘",()->setMode(SketchView.Mode.DRAW));selectButton=button("⌖ 选中",()->setMode(SketchView.Mode.SELECT));orbitButton=button("↻ 视角",()->setMode(SketchView.Mode.ORBIT));
        modes.addView(drawButton,new LinearLayout.LayoutParams(0,dp(43),1));gap(modes,7);modes.addView(selectButton,new LinearLayout.LayoutParams(0,dp(43),1));gap(modes,7);modes.addView(orbitButton,new LinearLayout.LayoutParams(0,dp(43),1));controls.addView(modes);
        LinearLayout tools=row();tools.setPadding(0,dp(9),0,dp(6));
        surfaceButton=button("贴面",()->setSurface(true));spaceButton=button("悬空",()->setSurface(false));
        tools.addView(surfaceButton,new LinearLayout.LayoutParams(dp(53),dp(32)));gap(tools,5);tools.addView(spaceButton,new LinearLayout.LayoutParams(dp(53),dp(32)));
        Space spacer=new Space(this);tools.addView(spacer,new LinearLayout.LayoutParams(0,1,1));
        tools.addView(button("撤回",this::undo),new LinearLayout.LayoutParams(dp(52),dp(32)));gap(tools,5);tools.addView(button("重做",this::redo),new LinearLayout.LayoutParams(dp(52),dp(32)));controls.addView(tools);
        LinearLayout brush=row();palette=row();brush.addView(palette,new LinearLayout.LayoutParams(0,dp(38),1));
        SeekBar width=new SeekBar(this);width.setMax(13);width.setProgress(3);width.setContentDescription("笔刷粗细");
        width.setOnSeekBarChangeListener(new SimpleSeek(){public void onProgressChanged(SeekBar b,int value,boolean user){if(user)scene.brushDp=2+value;}});
        brush.addView(width,new LinearLayout.LayoutParams(dp(105),dp(36)));controls.addView(brush);
        cameraPanel=new LinearLayout(this);cameraPanel.setOrientation(LinearLayout.VERTICAL);cameraPanel.setVisibility(View.GONE);controls.addView(cameraPanel);
        updatePalette();updateModes();
    }
    private void updatePalette() {
        palette.removeAllViews();
        for (int color:new int[]{0xffb9ee8a,0xffffd780,0xffff8e91,0xff9dbbff,0xffecefe9,0xff303b35}) {
            View chip=new View(this) {
                final Paint p=new Paint(3);
                @Override protected void onDraw(Canvas c) {
                    float x=getWidth()/2f,y=getHeight()/2f;
                    if (scene.color==color) {p.setColor(ACCENT);c.drawCircle(x,y,dp(14),p);p.setColor(PANEL);c.drawCircle(x,y,dp(12),p);}
                    p.setColor(color);c.drawCircle(x,y,dp(9),p);
                }
            };
            chip.setContentDescription("笔刷颜色 "+Integer.toHexString(color));chip.setOnClickListener(v->{scene.color=color;
                synchronized(scene){if(scene.selected>=0){scene.strokes.get(scene.selected).color=color;recordEdit();}}
                updatePalette();canvas.requestFrame();
            });palette.addView(chip,new LinearLayout.LayoutParams(0,dp(36),1));
        }
    }
    private void active(Button b,boolean selected) {b.setTextColor(selected?BG:TEXT);b.setBackground(background(selected?ACCENT:PANEL,12));}
    private void updateModes() {
        active(drawButton,canvas.mode==SketchView.Mode.DRAW);active(selectButton,canvas.mode==SketchView.Mode.SELECT);active(orbitButton,canvas.mode==SketchView.Mode.ORBIT);
        active(surfaceButton,scene.surfaceMode);active(spaceButton,!scene.surfaceMode);
        cameraPanel.setVisibility(canvas.mode==SketchView.Mode.ORBIT?View.VISIBLE:View.GONE);
        if(canvas.mode==SketchView.Mode.ORBIT) buildCameraControls();
    }
    private void setMode(SketchView.Mode mode) {
        if(busy)return;canvas.setMode(mode);if(mode!=SketchView.Mode.SELECT)inspector.setVisibility(View.GONE);updateModes();
        tip.setText(mode==SketchView.Mode.DRAW?"单指手绘 · 双指平移缩放":mode==SketchView.Mode.SELECT?"单指选中或拖对象 · 双指平移缩放":"单指环绕 · 双指平移缩放 · 正视可复位");
    }
    private void setSurface(boolean value) {scene.surfaceMode=value;setMode(SketchView.Mode.DRAW);updateModes();}
    private void home() {synchronized(scene){scene.home();}canvas.requestFrame();if(canvas.mode==SketchView.Mode.ORBIT)buildCameraControls();scheduleSave();}
    private void buildCameraControls() {
        cameraPanel.removeAllViews();
        addSlider(cameraPanel,"视角 X",-85,85,scene.pitch,1,value->{scene.pitch=value;canvas.requestFrame();},this::scheduleSave);
        addSlider(cameraPanel,"视角 Y",-180,180,scene.yaw,1,value->{scene.yaw=value;canvas.requestFrame();},this::scheduleSave);
        addSlider(cameraPanel,"视角 Z",-180,180,scene.roll,1,value->{scene.roll=value;canvas.requestFrame();},this::scheduleSave);
    }
    interface ValueChange {void set(float value);}
    static abstract class SimpleSeek implements SeekBar.OnSeekBarChangeListener {public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){} }
    private void addSlider(LinearLayout parent,String label,float min,float max,float value,int decimals,ValueChange change,Runnable commit) {
        LinearLayout line=row();TextView name=text(label,11,MUTED);line.addView(name,new LinearLayout.LayoutParams(dp(52),dp(32)));
        SeekBar bar=new SeekBar(this);bar.setMax(1000);bar.setProgress(Math.round((value-min)/(max-min)*1000));bar.setContentDescription(label);
        line.addView(bar,new LinearLayout.LayoutParams(0,dp(32),1));
        TextView number=text(format(value,decimals),11,TEXT);number.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);line.addView(number,new LinearLayout.LayoutParams(dp(45),dp(32)));
        bar.setOnSeekBarChangeListener(new SimpleSeek(){
            public void onProgressChanged(SeekBar b,int progress,boolean user){if(!user)return;float v=min+(max-min)*progress/1000; synchronized(scene){change.set(v);scene.changed();}number.setText(format(v,decimals));}
            public void onStopTrackingTouch(SeekBar b){commit.run();}
        });parent.addView(line);
    }
    private static String format(float v,int decimals) {return decimals==2?String.format(Locale.US,"%+.2f",v):String.format(Locale.US,"%+.0f°",v);}
    private LinearLayout beginInspector(String name,Runnable close){
        inspector.removeAllViews();LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout heading=row();TextView title=text(name,13,TEXT);title.setSingleLine(true);title.setContentDescription("拖动编辑框");heading.addView(title,new LinearLayout.LayoutParams(0,dp(32),1));
        Button fold=button(inspectorCollapsed?"展开":"收起",()->{});fold.setTextSize(11);fold.setPadding(0,0,0,0);fold.setContentDescription(inspectorCollapsed?"展开编辑框":"收起编辑框");
        fold.setOnClickListener(v->{inspectorCollapsed=!inspectorCollapsed;content.setVisibility(inspectorCollapsed?View.GONE:View.VISIBLE);fold.setText(inspectorCollapsed?"展开":"收起");fold.setContentDescription(inspectorCollapsed?"展开编辑框":"收起编辑框");});heading.addView(fold,new LinearLayout.LayoutParams(dp(42),dp(32)));
        Button dismiss=button("×",close);dismiss.setContentDescription("关闭编辑框");heading.addView(dismiss,new LinearLayout.LayoutParams(dp(30),dp(32)));inspector.addView(heading);inspector.addView(content);content.setVisibility(inspectorCollapsed?View.GONE:View.VISIBLE);
        final float[] drag=new float[4];title.setOnTouchListener((v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){drag[0]=e.getRawX();drag[1]=e.getRawY();drag[2]=inspector.getTranslationX();drag[3]=inspector.getTranslationY();return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE){float tx=drag[2]+e.getRawX()-drag[0],ty=drag[3]+e.getRawY()-drag[1];inspector.setTranslationX(Math.max(-inspector.getLeft(),Math.min(stage.getWidth()-inspector.getRight(),tx)));inspector.setTranslationY(Math.max(-inspector.getTop(),Math.min(stage.getHeight()-inspector.getBottom(),ty)));return true;}return true;
        });return content;
    }
    private void compactParameters(LinearLayout content,String[] names,String[] tabs,float[] min,float[] max,float[] values,int[] decimals,ValueChange[] changes){
        LinearLayout choices=row(),slider=new LinearLayout(this);slider.setOrientation(LinearLayout.VERTICAL);Button[] buttons=new Button[names.length];content.addView(choices);content.addView(slider);
        for(int i=0;i<names.length;i++){final int selected=i;Button tab=button(tabs[i],()->{
            slider.removeAllViews();for(int j=0;j<buttons.length;j++)buttons[j].setTextColor(j==selected?ACCENT:MUTED);
            addSlider(slider,names[selected],min[selected],max[selected],values[selected],decimals[selected],v->{values[selected]=v;changes[selected].set(v);},this::recordEdit);
        });tab.setTextSize(11);tab.setPadding(dp(2),0,dp(2),0);tab.setContentDescription("编辑 "+names[i]);buttons[i]=tab;choices.addView(tab,new LinearLayout.LayoutParams(0,dp(32),1));}buttons[0].performClick();
    }
    @Override public void onSelected(int index) {
        scene.selectedCutout=-1;
        if(index<0){inspector.setVisibility(View.GONE);return;}
        Scene.Stroke s=scene.strokes.get(index);
        LinearLayout content=beginInspector(String.format(Locale.US,"笔画 %02d",index+1),()->{scene.selected=-1;inspector.setVisibility(View.GONE);canvas.requestFrame();});
        compactParameters(content,new String[]{"前后","旋转 X","旋转 Y","旋转 Z"},new String[]{"前后","X","Y","Z"},new float[]{-1.5f,-180,-180,-180},new float[]{1.5f,180,180,180},new float[]{s.z,s.rx,s.ry,s.rz},new int[]{2,1,1,1},new ValueChange[]{v->{s.z=v;canvas.requestFrame();},v->{s.rx=v;canvas.requestFrame();},v->{s.ry=v;canvas.requestFrame();},v->{s.rz=v;canvas.requestFrame();}});
        LinearLayout actions=row();
        actions.addView(button("归零角度",()->{s.rx=s.ry=s.rz=0;recordEdit();onSelected(scene.selected);canvas.requestFrame();}),new LinearLayout.LayoutParams(0,dp(32),1));gap(actions,5);
        actions.addView(button("复制",()->{synchronized(scene){Scene.Stroke copy=s.copy();copy.x+=.06f;copy.z+=.03f;scene.strokes.add(copy);scene.selected=scene.strokes.size()-1;}recordEdit();onSelected(scene.selected);canvas.requestFrame();}),new LinearLayout.LayoutParams(dp(49),dp(32)));gap(actions,5);
        actions.addView(button("删除",()->{synchronized(scene){scene.strokes.remove(scene.selected);scene.selected=-1;}inspector.setVisibility(View.GONE);recordEdit();canvas.requestFrame();}),new LinearLayout.LayoutParams(dp(49),dp(32)));content.addView(actions);
        inspector.setVisibility(View.VISIBLE);
    }
    @Override public void onEdited(){recordEdit();}
    @Override public void onCameraChanged(){buildCameraControls();scheduleSave();}
    private Scene snapshot(){return scene.snapshot();}
    private void updateCount(){count.setText(scene.strokes.size()+" 笔"+(scene.cutouts.isEmpty()?"":" · "+scene.cutouts.size()+" 对象"));}
    private void resetHistory(){undo.clear();redo.clear();undo.add(snapshot());updateCount();}
    private void recordEdit(){if(restoring)return;undo.add(snapshot());if(undo.size()>31)undo.remove(0);redo.clear();updateCount();scheduleSave();}
    private void restoreStrokes(Scene state){synchronized(scene){scene.strokes.clear();for(Scene.Stroke s:state.strokes)scene.strokes.add(s.copy());scene.cutouts.clear();for(Scene.Cutout c:state.cutouts)scene.cutouts.add(c.copy());scene.selected=scene.selectedCutout=-1;}inspector.setVisibility(View.GONE);updateCount();canvas.requestFrame();scheduleSave();}
    private void undo(){if(busy||undo.size()<2)return;redo.add(undo.remove(undo.size()-1));restoreStrokes(undo.get(undo.size()-1));}
    private void redo(){if(busy||redo.isEmpty())return;Scene state=redo.remove(redo.size()-1);undo.add(state);restoreStrokes(state);}
    private void scheduleSave(){if(scene.image==null||destroyed)return;saveGeneration++;handler.removeCallbacks(autosave);handler.postDelayed(autosave,900);}
    private void queueSave(){
        if(scene.image==null||worker.isShutdown()||!saveQueued.compareAndSet(false,true))return;
        worker.execute(()->{saveQueued.set(false);saveLocally();});
    }
    private void saveLocally(){
        long generation=saveGeneration;if(scene.image==null)return;Scene composition=scene.snapshot();
        if(generation==savedGeneration&&composition.revision==savedRevision)return;
        File target=new File(getFilesDir(),"autosave.dsketch"),part=new File(getFilesDir(),"autosave.tmp");
        try{ProjectStore.write(new FileOutputStream(part),composition);if(!part.renameTo(target))throw new IOException("自动保存失败");savedGeneration=generation;savedRevision=composition.revision;}
        catch(Exception e){android.util.Log.e("DepthSketch","Autosave failed",e);ui(()->status.setText("自动保存失败，请从菜单导出项目"));}
    }
    private boolean ready(){if(scene.image!=null&&!busy)return true;if(!busy)Toast.makeText(this,"请先选择一张照片",Toast.LENGTH_SHORT).show();return false;}
    private void setBusy(boolean value,String message){busy=value;canvas.setEnabled(!value);status.setText(message);getWindow().getDecorView().setKeepScreenOn(value);}
    private void ui(Runnable task){handler.post(()->{if(!destroyed)task.run();});}
    private void error(String title,Throwable error){android.util.Log.e("DepthSketch",title,error);new AlertDialog.Builder(this).setTitle(title).setMessage(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage()).setPositiveButton("知道了",null).show();}
    private void pickImage(){if(busy)return;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("image/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK_IMAGE);}
    private void confirmReplace(Runnable action){if(!scene.strokes.isEmpty()||!scene.cutouts.isEmpty())new AlertDialog.Builder(this).setTitle("替换当前画布？").setMessage("如需保留当前作品，请先在菜单中导出项目。替换后会开始新的画布。").setNegativeButton("取消",null).setPositiveButton("开始新画布",(d,w)->action.run()).show();else action.run();}
    private void loadSample(){if(busy)return;confirmReplace(()->estimate(null));}
    private Bitmap decode(Uri uri)throws Exception{return ImageFiles.decode(this,uri);}
    void estimate(Uri uri){
        handler.removeCallbacks(autosave);setBusy(true,"正在本机估计深度，请稍候…");
        worker.execute(()->{
            try{
                Bitmap image=decode(uri);if(image==null)throw new IOException("无法解码照片");
                if(image.getWidth()<32||image.getHeight()<32||Math.max((float)image.getWidth()/image.getHeight(),(float)image.getHeight()/image.getWidth())>4)throw new IOException("请选择长宽比不超过 4:1、边长至少 32 像素的照片");
                DepthEstimator.Result depth=estimator.estimate(image);
                android.util.Log.i("DepthSketch","DEPTH_OK "+depth.width+"x"+depth.height+" "+depth.millis+"ms");
                ui(()->{synchronized(scene){scene.setImage(image,depth.values,depth.width,depth.height);}welcome.setVisibility(View.GONE);inspector.setVisibility(View.GONE);photoButton.setText("底图");scene.showPhoto=true;resetHistory();
                    setBusy(false,String.format(Locale.US,"本地估深完成 · %.1f 秒",depth.millis/1000f));setMode(SketchView.Mode.DRAW);canvas.requestFrame();scheduleSave();});
            }catch(Throwable e){ui(()->{setBusy(false,"估深未完成，可重新选择照片");error("照片处理失败",e);});}
        });
    }
    void applyProject(Scene loaded){
        synchronized(scene){scene.setImage(loaded.image,loaded.depth,loaded.depthWidth,loaded.depthHeight);scene.strokes.addAll(loaded.strokes);scene.cutouts.addAll(loaded.cutouts);scene.relief=loaded.relief;scene.pitch=loaded.pitch;scene.yaw=loaded.yaw;scene.roll=loaded.roll;scene.zoom=loaded.zoom;scene.panX=loaded.panX;scene.panY=loaded.panY;scene.showPhoto=loaded.showPhoto;scene.geometryRevision++;}
        welcome.setVisibility(View.GONE);inspector.setVisibility(View.GONE);photoButton.setText(scene.showPhoto?"底图":"纯笔画");resetHistory();canvas.mode=SketchView.Mode.SELECT;updateModes();tip.setText("项目已打开 · 点选笔画继续编辑");canvas.requestFrame();
    }
    private void showMenu(){
        if(busy)return;
        String[] options={"导出图片 PNG","导出可编辑项目","打开项目","笔画列表","示例照片","使用说明","从新图片点选抠图","对象列表","预览重力动态壁纸","直接导入图片素材"};
        new AlertDialog.Builder(this).setTitle("画布与作品").setItems(options,(d,which)->{
            switch(which){
                case 0:if(ready())createDocument("image/png","深绘作品.png",SAVE_PNG);break;
                case 1:if(ready())createDocument("application/octet-stream","深绘作品.dsketch",SAVE_PROJECT);break;
                case 2:Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,OPEN_PROJECT);break;
                case 3:showStrokeList();break;
                case 4:loadSample();break;
                case 6:if(ready()){Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.setType("image/*");pick.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(pick,PICK_CUTOUT);}break;
                case 7:showCutoutList();break;
                case 8:publishWallpaper();break;
                case 9:if(ready()){Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.setType("image/*");pick.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(pick,PICK_MATERIAL);}break;
                case 5:new AlertDialog.Builder(this).setTitle("每一笔，都有自己的空间").setMessage("1. 导入照片后，手机会离线估计一次深度。\n\n2. 贴面笔画跟随照片表面；悬空笔画绘制在统一深度的平面。\n\n3. 点“选中”再点击笔画，拖动悬浮窗中的前后与 X/Y/Z 角度滑杆。窗口标题可以拖动。菜单的笔画列表也能选中被遮挡的笔画。\n\n4. 点“视角”后单指环绕、双指缩放，三轴滑杆调整观察角度。手绘模式会回到正视图。\n\n5. 作品自动保存在本机；导出 .dsketch 项目可保留照片、深度和每一笔，PNG 保存当前视角。\n\n单张照片只有可见表面的相对深度，旋转到侧后方会出现缺失区域。角度以笔画中心为轴，前后数值不是米。\n\n深度模型：Depth Anything V2 Small（Apache-2.0）。推理：ONNX Runtime（MIT）。示例照片来自 Depth-Anything-V2 仓库。无网络权限，不依赖华为服务。").setPositiveButton("开始画吧",null).show();break;
            }
        }).show();
    }
    private void showStrokeList(){
        if(!ready())return;if(scene.strokes.isEmpty()){Toast.makeText(this,"还没有笔画，先画几笔吧",Toast.LENGTH_SHORT).show();return;}
        String[] names=new String[scene.strokes.size()];for(int i=0;i<names.length;i++)names[i]=String.format(Locale.US,"笔画 %02d · %d 个采样点",i+1,scene.strokes.get(i).points.size());
        new AlertDialog.Builder(this).setTitle("选择一笔").setItems(names,(d,which)->{setMode(SketchView.Mode.SELECT);scene.selected=which;onSelected(which);canvas.requestFrame();}).show();
    }
    private void createDocument(String mime,String name,int request){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType(mime);i.addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_TITLE,name);startActivityForResult(i,request);}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null)return;
        if(request==CUTOUT_DONE){receiveCutout(data.getStringExtra("cutout_file"));return;}
        if(data.getData()==null)return;Uri uri=data.getData();
        if(request==PICK_CUTOUT){importCutout(uri);return;}
        if(request==PICK_MATERIAL){importMaterial(uri);return;}
        if(request==PICK_IMAGE){confirmReplace(()->estimate(uri));return;}
        if(request==OPEN_PROJECT){confirmReplace(()->{
            setBusy(true,"正在打开项目…");worker.execute(()->{try(InputStream in=getContentResolver().openInputStream(uri)){Scene loaded=ProjectStore.read(in);ui(()->{applyProject(loaded);setBusy(false,"已打开可编辑项目");scheduleSave();});}catch(Exception e){ui(()->{setBusy(false,"项目未能打开");error("打开失败",e);});}});
        });return;}
        if(request==SAVE_PROJECT){setBusy(true,"正在导出项目…");worker.execute(()->{try{ProjectStore.write(getContentResolver().openOutputStream(uri),scene);ui(()->setBusy(false,"项目已导出 · 可再次打开编辑"));}catch(Exception e){ui(()->{setBusy(false,"导出失败");error("无法保存项目",e);});}});return;}
        if(request==SAVE_PNG){exportPNG(uri);}
    }
    void exportPNG(Uri uri){
        setBusy(true,"正在导出当前视角…");int selected=scene.selected,selectedObject=scene.selectedCutout;scene.selected=scene.selectedCutout=-1;canvas.requestFrame();
        handler.postDelayed(()->{
            if(destroyed)return;
            Bitmap bitmap=Bitmap.createBitmap(canvas.getWidth(),canvas.getHeight(),Bitmap.Config.ARGB_8888);
            PixelCopy.request(canvas,bitmap,result->{scene.selected=selected;scene.selectedCutout=selectedObject;canvas.requestFrame();
                if(result!=PixelCopy.SUCCESS){bitmap.recycle();setBusy(false,"截图失败，请重试");return;}
                worker.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(uri)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("无法编码图片");ui(()->setBusy(false,"PNG 已保存"));}catch(Exception e){ui(()->{setBusy(false,"导出失败");error("无法保存图片",e);});}finally{bitmap.recycle();}});
            },handler);
        },180);
    }
    void importCutout(Uri uri){
        if(!ready())return;if(scene.cutouts.size()>=12){Toast.makeText(this,"一个画布最多放 12 个抠图对象",Toast.LENGTH_SHORT).show();return;}
        canvas.finishStroke();worker.execute(estimator::close);startActivityForResult(new Intent(this,CutoutActivity.class).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),CUTOUT_DONE);
    }
    void importMaterial(Uri uri){
        if(!ready())return;if(scene.cutouts.size()>=12){Toast.makeText(this,"一个画布最多放 12 个对象",Toast.LENGTH_SHORT).show();return;}
        canvas.finishStroke();handler.removeCallbacks(autosave);setBusy(true,"正在导入素材并估计深度…");
        worker.execute(()->{
            Bitmap input=null,image=null;
            try{
                image=ImageFiles.decode(this,uri);if(image==null)throw new IOException("无法读取素材图片");input=ImageMaterial.depthInput(image);
                DepthEstimator.Result depth=estimator.estimate(input);Scene.Cutout material=ImageMaterial.create(image,depth);
                ui(()->{synchronized(scene){scene.cutouts.add(material);scene.selected=-1;scene.selectedCutout=scene.cutouts.size()-1;}setBusy(false,"素材已直接加入 · 可调整位置与深度");setMode(SketchView.Mode.SELECT);onCutoutSelected(scene.selectedCutout);recordEdit();canvas.requestFrame();});
            }catch(Exception e){ui(()->{setBusy(false,"素材未能导入");error("无法导入素材",e);});}
            finally{estimator.close();if(input!=null&&input!=image)input.recycle();}
        });
    }
    private void receiveCutout(String name){
        if(name==null||!name.matches("cutout-[0-9]+\\.dsketch"))return;
        File file=new File(getCacheDir(),name);setBusy(true,"正在把带深度对象加入画布…");
        worker.execute(()->{
            try(InputStream input=new FileInputStream(file)){
                Scene transfer=ProjectStore.read(input);if(transfer.cutouts.size()!=1)throw new IOException("抠图对象内容无效");
                Scene.Cutout cutout=transfer.cutouts.get(0);
                ui(()->{synchronized(scene){scene.cutouts.add(cutout);scene.selected=-1;scene.selectedCutout=scene.cutouts.size()-1;}
                    setBusy(false,"带深度对象已加入 · 拖动可调整位置");setMode(SketchView.Mode.SELECT);onCutoutSelected(scene.selectedCutout);recordEdit();canvas.requestFrame();});
            }catch(Exception e){ui(()->{setBusy(false,"添加对象失败");error("无法加入画布",e);});}
            finally{file.delete();}
        });
    }
    private void showCutoutList(){
        if(!ready())return;if(scene.cutouts.isEmpty()){Toast.makeText(this,"先从菜单选择新图片点选抠图",Toast.LENGTH_SHORT).show();return;}
        String[] names=new String[scene.cutouts.size()];for(int i=0;i<names.length;i++)names[i]="抠图对象 "+(i+1);
        new AlertDialog.Builder(this).setTitle("选择抠图对象").setItems(names,(d,i)->{setMode(SketchView.Mode.SELECT);onCutoutSelected(i);canvas.requestFrame();}).show();
    }
    @Override public void onCutoutSelected(int index){
        if(index<0||index>=scene.cutouts.size()){inspector.setVisibility(View.GONE);return;}
        scene.selected=-1;scene.selectedCutout=index;Scene.Cutout c=scene.cutouts.get(index);
        LinearLayout content=beginInspector("抠图对象 "+(index+1),()->{scene.selectedCutout=-1;inspector.setVisibility(View.GONE);canvas.requestFrame();});
        compactParameters(content,new String[]{"对象前后","对象大小","表面起伏","对象 X","对象 Y","对象 Z","边缘柔化"},new String[]{"前后","大小","起伏","X","Y","Z","边缘"},new float[]{-1.5f,.1f,0,-180,-180,-180,0},new float[]{1.5f,2,1.5f,180,180,180,12},new float[]{c.z,c.scale,c.relief,c.rx,c.ry,c.rz,c.feather},new int[]{2,2,2,1,1,1,2},new ValueChange[]{v->{c.z=v;canvas.requestFrame();},v->{c.scale=v;canvas.requestFrame();},v->{c.relief=v;canvas.requestFrame();},v->{c.rx=v;canvas.requestFrame();},v->{c.ry=v;canvas.requestFrame();},v->{c.rz=v;canvas.requestFrame();},v->{c.feather=v;canvas.requestFrame();}});
        LinearLayout actions=row();actions.addView(button("复制对象",()->{if(scene.cutouts.size()>=12){Toast.makeText(this,"Maximum 12 cutout objects",Toast.LENGTH_SHORT).show();return;}synchronized(scene){Scene.Cutout copy=c.copy();copy.x+=.08f;copy.z+=.03f;scene.cutouts.add(copy);}recordEdit();onCutoutSelected(scene.cutouts.size()-1);canvas.requestFrame();}),new LinearLayout.LayoutParams(0,dp(34),1));
        gap(actions,8);actions.addView(button("删除对象",()->{synchronized(scene){scene.cutouts.remove(scene.selectedCutout);scene.selectedCutout=-1;}inspector.setVisibility(View.GONE);recordEdit();canvas.requestFrame();}),new LinearLayout.LayoutParams(0,dp(34),1));content.addView(actions);inspector.setVisibility(View.VISIBLE);
    }
    void publishWallpaper(){
        if(!ready())return;canvas.finishStroke();Scene composition=scene.snapshot();composition.selected=composition.selectedCutout=-1;composition.showPhoto=true;
        handler.removeCallbacks(autosave);
        setBusy(true,"正在准备动态壁纸预览…");
        worker.execute(()->{try{WallpaperFiles.publish(this,composition);estimator.close();ui(()->{setBusy(false,"壁纸作品已保存");startActivity(new Intent(this,WallpaperPreviewActivity.class));});}
            catch(Exception e){ui(()->{setBusy(false,"壁纸预览未能打开");error("保存壁纸失败",e);});}});
    }
    void reestimateComposition(){
        if(!ready())return;canvas.finishStroke();handler.removeCallbacks(autosave);Scene original=scene.snapshot();setBusy(true,"正在合成整幅作品…");
        canvas.queueEvent(()->{
            try{Bitmap composite=SceneCapture.composite(original);worker.execute(()->{
                try{
                    ui(()->status.setText("正在为合成图片重新估计深度…"));DepthEstimator.Result depth=estimator.estimate(composite);Scene estimated=new Scene();estimated.setImage(composite,depth.values,depth.width,depth.height);estimated.relief=original.relief;
                    String prefix="reestimate-"+System.nanoTime();File sourceFile=new File(getCacheDir(),prefix+"-source.dsketch"),resultFile=new File(getCacheDir(),prefix+"-result.dsketch");
                    ProjectStore.write(new FileOutputStream(sourceFile),original);ProjectStore.write(new FileOutputStream(resultFile),estimated);
                    ui(()->{setBusy(false,"已生成独立重估结果 · 原作品保留");startActivity(new Intent(this,ReestimatedPreviewActivity.class).putExtra("source_file",sourceFile.getName()).putExtra("result_file",resultFile.getName()));});
                }catch(Exception e){ui(()->{setBusy(false,"合成重估未完成");error("无法重新估计深度",e);});}finally{estimator.close();composite.recycle();}
            });}catch(Exception e){ui(()->{setBusy(false,"合成重估未完成");error("无法合成作品",e);});}
        });canvas.requestFrame();
    }
    @Override protected void onResume(){super.onResume();if(canvas!=null)canvas.onResume();}
    @Override protected void onPause(){if(canvas!=null){canvas.finishStroke();canvas.onPause();}handler.removeCallbacks(autosave);queueSave();super.onPause();}
    @Override protected void onDestroy(){destroyed=true;handler.removeCallbacksAndMessages(null);worker.execute(estimator::close);worker.shutdown();super.onDestroy();}
}
