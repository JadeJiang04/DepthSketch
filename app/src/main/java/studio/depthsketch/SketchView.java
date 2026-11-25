package studio.depthsketch;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

public final class SketchView extends GLSurfaceView {
    public enum Mode { DRAW, SELECT, ORBIT }
    public Mode mode=Mode.DRAW;
    public final Scene scene;
    final SceneRenderer renderer;
    public interface Listener { void onSelected(int index); void onCutoutSelected(int index); void onEdited(); void onCameraChanged(); }
    public Listener listener;
    private Scene.Stroke drawing;
    private float previousX,previousY,downX,downY,lastDrawX,lastDrawY;
    private boolean multiTouch;
    private boolean trackingPan,cameraGestureChanged;
    private float panFocusX,panFocusY;
    private int dragCutout=-1;
    private boolean draggedCutout;
    private float grabX,grabY;
    private final ScaleGestureDetector pinch;
    private boolean framePending;
    private final android.view.Choreographer.FrameCallback drawFrame=time->{framePending=false;super.requestRender();};

    /** Coalesce render requests to display frames without dropping any historical pen samples. */
    public void requestFrame(){
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()){post(this::requestFrame);return;}
        if(!framePending){framePending=true;android.view.Choreographer.getInstance().postFrameCallback(drawFrame);}
    }

    public SketchView(Context context,Scene scene) {
        super(context); this.scene=scene;
        setEGLContextClientVersion(2); setEGLConfigChooser(8,8,8,8,16,0);
        renderer=new SceneRenderer(scene); setRenderer(renderer); setRenderMode(RENDERMODE_WHEN_DIRTY);
        setPreserveEGLContextOnPause(true); setContentDescription("三维绘画画布");
        pinch=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                synchronized (scene) { renderer.zoomAt(detector.getScaleFactor(),detector.getFocusX(),detector.getFocusY());cameraGestureChanged=true; }
                requestFrame(); return true;
            }
        });
    }
    public void setMode(Mode next) {
        finishStroke(); mode=next;
        if (mode==Mode.DRAW) { synchronized (scene) { scene.pitch=scene.yaw=scene.roll=0;scene.changed();scene.selected=scene.selectedCutout=-1; } }
        requestFrame();
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (scene.image==null || !isEnabled()) return true;
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&trackingPan&&event.getPointerCount()>=2){
            float fx=0,fy=0;for(int i=0;i<event.getPointerCount();i++){fx+=event.getX(i);fy+=event.getY(i);}fx/=event.getPointerCount();fy/=event.getPointerCount();
            synchronized(scene){renderer.panByPixels(fx-panFocusX,fy-panFocusY);}cameraGestureChanged=true;panFocusX=fx;panFocusY=fy;
        }
        pinch.onTouchEvent(event);
        synchronized (scene) {
            float x=event.getX(),y=event.getY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    multiTouch=false;trackingPan=false;cameraGestureChanged=false; previousX=downX=x; previousY=downY=y;
                    dragCutout=-1;draggedCutout=false;
                    if(mode==Mode.SELECT&&scene.selectedCutout>=0){
                        renderer.updateCamera();Scene.Cutout c=scene.cutouts.get(scene.selectedCutout);
                        if(renderer.pickCutout(x,y,c)!=null){float[] hit=renderer.screenPlane(x,y,c.z);if(hit!=null){dragCutout=scene.selectedCutout;grabX=hit[0]-c.x;grabY=hit[1]-c.y;}}
                    }
                    if (mode==Mode.DRAW) {
                        float[] p=renderer.drawingPoint(x,y);
                        if (p!=null && scene.strokes.size()<2000) {
                            drawing=new Scene.Stroke(); drawing.color=scene.color;
                            drawing.radius=scene.brushDp*getResources().getDisplayMetrics().density*worldPerPixel()*.5f;
                            drawing.points.add(p); scene.strokes.add(drawing); scene.selected=scene.selectedCutout=-1;
                            lastDrawX=x;lastDrawY=y;
                        }
                    }
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    multiTouch=true;
                    trackingPan=true;panFocusX=panFocusY=0;for(int i=0;i<event.getPointerCount();i++){panFocusX+=event.getX(i);panFocusY+=event.getY(i);}panFocusX/=event.getPointerCount();panFocusY/=event.getPointerCount();
                    if(draggedCutout&&listener!=null)listener.onEdited();dragCutout=-1;
                    // A second finger cancels this gesture's provisional stroke.
                    if (drawing!=null) { scene.strokes.remove(drawing);drawing=null; }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (multiTouch || event.getPointerCount()>1) break;
                    if (mode==Mode.DRAW && drawing!=null) {
                        for (int i=0;i<event.getHistorySize();i++) addPoint(event.getHistoricalX(i),event.getHistoricalY(i));
                        addPoint(x,y);
                    } else if (mode==Mode.ORBIT) {
                        scene.yaw=wrap(scene.yaw+(x-previousX)*.32f);
                        scene.pitch=Math.max(-85,Math.min(85,scene.pitch+(y-previousY)*.32f));
                    } else if(mode==Mode.SELECT&&dragCutout>=0&&Math.hypot(x-downX,y-downY)>6*getResources().getDisplayMetrics().density){
                        Scene.Cutout c=scene.cutouts.get(dragCutout);float[] hit=renderer.screenPlane(x,y,c.z);
                        if(hit!=null){c.x=Math.max(-5,Math.min(5,hit[0]-grabX));c.y=Math.max(-5,Math.min(5,hit[1]-grabY));draggedCutout=true;}
                    }
                    previousX=x;previousY=y; break;
                case MotionEvent.ACTION_POINTER_UP:
                    trackingPan=event.getPointerCount()>2;
                    if(trackingPan){panFocusX=panFocusY=0;for(int i=0;i<event.getPointerCount();i++)if(i!=event.getActionIndex()){panFocusX+=event.getX(i);panFocusY+=event.getY(i);}panFocusX/=event.getPointerCount()-1;panFocusY/=event.getPointerCount()-1;}
                    break;
                case MotionEvent.ACTION_UP:
                    if(!multiTouch&&mode==Mode.DRAW&&drawing!=null)addPoint(x,y);
                    if (!multiTouch && mode==Mode.SELECT&&!draggedCutout && Math.hypot(x-downX,y-downY)<20*getResources().getDisplayMetrics().density) select(x,y);
                    if(draggedCutout&&listener!=null)listener.onEdited();dragCutout=-1;
                    if ((mode==Mode.ORBIT||cameraGestureChanged) && listener!=null) listener.onCameraChanged();trackingPan=false;
                    finishStroke(); performClick(); break;
                case MotionEvent.ACTION_CANCEL:
                    if(cameraGestureChanged&&listener!=null)listener.onCameraChanged();trackingPan=false;
                    if(draggedCutout&&listener!=null)listener.onEdited();dragCutout=-1;
                    if (drawing!=null) { scene.strokes.remove(drawing);drawing=null; }
                    break;
            }
            scene.changed();
        }
        requestFrame(); return true;
    }
    private float worldPerPixel() {
        float h=2.56f,w=h*getWidth()/Math.max(1f,getHeight());
        if (w<scene.aspect*2.36f) h=scene.aspect*2.36f*getHeight()/Math.max(1f,getWidth());
        return h/Math.max(1,getHeight())/scene.zoom;
    }
    private void addPoint(float x,float y) {
        if (drawing.points.size()>=4000 || Math.hypot(x-lastDrawX,y-lastDrawY)<2*getResources().getDisplayMetrics().density) return;
        float[] p=renderer.drawingPoint(x,y);
        if (p!=null) { drawing.points.add(p);lastDrawX=x;lastDrawY=y; }
    }
    public void finishStroke() {
        synchronized (scene) {
            if (drawing!=null) { drawing.center();drawing=null; if (listener!=null) listener.onEdited(); }
        }
    }
    void select(float x,float y) {
        renderer.updateCamera();
        float tolerance=22*getResources().getDisplayMetrics().density;
        int chosen=-1;float nearestDepth=Float.POSITIVE_INFINITY;
        float[] object=new float[16],mvp=new float[16],projected=new float[4],input=new float[4];
        float[] current=new float[3],previous=new float[3];
        for (int i=scene.strokes.size()-1;i>=0;i--) {
            Scene.Stroke s=scene.strokes.get(i);boolean first=true;
            android.opengl.Matrix.setIdentityM(object,0);android.opengl.Matrix.translateM(object,0,s.x,s.y,s.z);
            android.opengl.Matrix.rotateM(object,0,s.rz,0,0,1);android.opengl.Matrix.rotateM(object,0,s.ry,0,1,0);android.opengl.Matrix.rotateM(object,0,s.rx,1,0,0);
            android.opengl.Matrix.multiplyMM(mvp,0,renderer.camera,0,object,0);
            for (float[] p:s.points) {
                input[0]=p[0];input[1]=p[1];input[2]=p[2];input[3]=1;
                android.opengl.Matrix.multiplyMV(projected,0,mvp,0,input,0);
                current[0]=(projected[0]/projected[3]+1)*renderer.width*.5f;
                current[1]=(1-projected[1]/projected[3])*renderer.height*.5f;current[2]=projected[2]/projected[3];
                float distance=first?(float)Math.hypot(current[0]-x,current[1]-y):segmentDistance(x,y,previous,current);
                if (distance<tolerance && current[2]<nearestDepth) { chosen=i;nearestDepth=current[2]; }
                float[] swap=previous;previous=current;current=swap;first=false;
            }
        }
        int chosenCutout=-1;
        for(int i=scene.cutouts.size()-1;i>=0;i--){float[] hit=renderer.pickCutout(x,y,scene.cutouts.get(i));if(hit!=null&&hit[3]<nearestDepth){nearestDepth=hit[3];chosenCutout=i;}}
        scene.selected=chosenCutout>=0?-1:chosen;scene.selectedCutout=chosenCutout;
        if(listener!=null){if(chosenCutout>=0)listener.onCutoutSelected(chosenCutout);else listener.onSelected(chosen);}
    }
    static float segmentDistance(float x,float y,float[] a,float[] b) {
        float dx=b[0]-a[0],dy=b[1]-a[1];
        float t=Math.max(0,Math.min(1,((x-a[0])*dx+(y-a[1])*dy)/Math.max(1e-8f,dx*dx+dy*dy)));
        return (float)Math.hypot(a[0]+t*dx-x,a[1]+t*dy-y);
    }
    static float wrap(float angle) { return (angle+540)%360-180; }
    @Override public boolean performClick() { super.performClick(); return true; }
}
