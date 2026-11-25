package studio.depthsketch;

import android.opengl.*;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** UI picking and GL rendering use separate matrices and only brief scene snapshots. */
final class SceneRenderer implements GLSurfaceView.Renderer {
    final Scene scene;
    final float[] camera=new float[16];
    volatile int width=1,height=1;
    private final float[] projection=new float[16],world=new float[16];
    final GpuScene gpu;
    SceneRenderer(Scene scene){this.scene=scene;gpu=new GpuScene(scene);Matrix.setIdentityM(camera,0);}
    @Override public void onSurfaceCreated(GL10 gl,EGLConfig config){gpu.init();}
    @Override public void onSurfaceChanged(GL10 gl,int w,int h){width=w;height=h;GLES20.glViewport(0,0,w,h);}
    void updateCamera() {
        float viewport=(float)width/height;
        float halfH=1.28f, halfW=halfH*viewport;
        if (halfW<scene.aspect*1.18f) { halfW=scene.aspect*1.18f; halfH=halfW/viewport; }
        Matrix.orthoM(projection,0,-halfW,halfW,-halfH,halfH,-20,20);
        Matrix.setIdentityM(world,0);Matrix.translateM(world,0,scene.panX,scene.panY,0); Matrix.scaleM(world,0,scene.zoom,scene.zoom,scene.zoom);
        Matrix.rotateM(world,0,scene.pitch,1,0,0); Matrix.rotateM(world,0,scene.yaw,0,1,0); Matrix.rotateM(world,0,scene.roll,0,0,1);
        Matrix.multiplyMM(camera,0,projection,0,world,0);
    }
    static float[] objectMatrix(Scene.Stroke s) {
        float[] m=new float[16]; Matrix.setIdentityM(m,0); Matrix.translateM(m,0,s.x,s.y,s.z);
        Matrix.rotateM(m,0,s.rz,0,0,1); Matrix.rotateM(m,0,s.ry,0,1,0); Matrix.rotateM(m,0,s.rx,1,0,0); return m;
    }
    float[] project(float[] p,Scene.Stroke s) {
        float[] v={p[0],p[1],p[2],1},result=new float[4];
        if (s!=null) { Matrix.multiplyMV(result,0,objectMatrix(s),0,v,0); v=result.clone(); }
        Matrix.multiplyMV(result,0,camera,0,v,0);
        return new float[]{(result[0]/result[3]+1)*width*.5f,(1-result[1]/result[3])*height*.5f,result[2]/result[3]};
    }
    float[] unproject(float x,float y,float z) {
        float[] inverse=new float[16],p={2*x/width-1,1-2*y/height,z,1},out=new float[4];
        if (!Matrix.invertM(inverse,0,camera,0)) return null;
        Matrix.multiplyMV(out,0,inverse,0,p,0);
        return new float[]{out[0]/out[3],out[1]/out[3],out[2]/out[3]};
    }
    float[] drawingPoint(float screenX,float screenY) {
        // Drawing mode uses the front view: one depth sample, no repeated matrix inversions.
        if(scene.pitch==0&&scene.yaw==0&&scene.roll==0){
            float halfH=1.28f,halfW=halfH*width/height;
            if(halfW<scene.aspect*1.18f){halfW=scene.aspect*1.18f;halfH=halfW*height/width;}
            float x=((2*screenX/width-1)*halfW-scene.panX)/scene.zoom,y=((1-2*screenY/height)*halfH-scene.panY)/scene.zoom;
            if(Math.abs(x)>scene.aspect||Math.abs(y)>1)return null;
            float z=scene.surfaceMode?scene.surface(x,y)+.014f:.5f;
            if(scene.surfaceMode&&!scene.cutouts.isEmpty()){
                updateCamera();for(Scene.Cutout c:scene.cutouts){float[] hit=pickCutout(screenX,screenY,c);if(hit!=null)z=Math.max(z,hit[2]+.014f);}
            }
            return new float[]{x,y,z};
        }
        updateCamera();
        float[] a=unproject(screenX,screenY,-1), b=unproject(screenX,screenY,1);
        if (a==null||b==null||Math.abs(b[2]-a[2])<1e-5f) return null;
        float z=scene.surfaceMode?0:.5f;
        float x=0,y=0;
        for (int i=0;i<8;i++) {
            float t=(z-a[2])/(b[2]-a[2]); x=a[0]+t*(b[0]-a[0]); y=a[1]+t*(b[1]-a[1]);
            if (scene.surfaceMode) z=scene.surface(x,y)+.014f;
        }
        if (Math.abs(x)>scene.aspect || Math.abs(y)>1) return null;
        return new float[]{x,y,z};
    }
    float[] screenPlane(float x,float y,float z){
        updateCamera();float[] a=unproject(x,y,-1),b=unproject(x,y,1);
        if(a==null||b==null||Math.abs(b[2]-a[2])<1e-6f)return null;
        float t=(z-a[2])/(b[2]-a[2]);return new float[]{a[0]+t*(b[0]-a[0]),a[1]+t*(b[1]-a[1]),z};
    }
    void panByPixels(float dx,float dy){
        float halfH=1.28f,halfW=halfH*width/height;
        if(halfW<scene.aspect*1.18f){halfW=scene.aspect*1.18f;halfH=halfW*height/width;}
        scene.panX=Math.max(-20,Math.min(20,scene.panX+2*dx*halfW/width));
        scene.panY=Math.max(-20,Math.min(20,scene.panY-2*dy*halfH/height));scene.changed();
    }
    void zoomAt(float factor,float focusX,float focusY){
        float halfH=1.28f,halfW=halfH*width/height;
        if(halfW<scene.aspect*1.18f){halfW=scene.aspect*1.18f;halfH=halfW*height/width;}
        float zoom=Math.max(.35f,Math.min(5,scene.zoom*factor)),ratio=zoom/scene.zoom;
        float x=(2*focusX/width-1)*halfW,y=(1-2*focusY/height)*halfH;
        scene.panX=Math.max(-20,Math.min(20,x-(x-scene.panX)*ratio));
        scene.panY=Math.max(-20,Math.min(20,y-(y-scene.panY)*ratio));scene.zoom=zoom;scene.changed();
    }
    float[] pickCutout(float screenX,float screenY,Scene.Cutout c){
        float[] object=new float[16],mvp=new float[16],inverse=new float[16],a=new float[4],b=new float[4];
        CutoutMesh.matrix(object,c);Matrix.multiplyMM(mvp,0,camera,0,object,0);if(!Matrix.invertM(inverse,0,mvp,0))return null;
        float[] p={2*screenX/width-1,1-2*screenY/height,-1,1};Matrix.multiplyMV(a,0,inverse,0,p,0);p[2]=1;Matrix.multiplyMV(b,0,inverse,0,p,0);
        for(int i=0;i<3;i++){a[i]/=a[3];b[i]/=b[3];}if(Math.abs(b[2]-a[2])<1e-7f)return null;
        float x=0,y=0,z=0;
        for(int i=0;i<8;i++){float t=(z-a[2])/(b[2]-a[2]);x=a[0]+t*(b[0]-a[0]);y=a[1]+t*(b[1]-a[1]);z=c.depthAt(x/(2*c.aspect())+.5f,.5f-y/2)-c.centerDepth;}
        float u=x/(2*c.aspect())+.5f,v=.5f-y/2;if(u<0||u>1||v<0||v>1)return null;
        int pixel=c.image.getPixel(Math.min(c.image.getWidth()-1,(int)(u*c.image.getWidth())),Math.min(c.image.getHeight()-1,(int)(v*c.image.getHeight())));
        if((pixel>>>24)<64)return null;
        float[] local={x,y,z,1},world=new float[4],clip=new float[4];Matrix.multiplyMV(world,0,object,0,local,0);Matrix.multiplyMV(clip,0,mvp,0,local,0);
        return new float[]{world[0],world[1],world[2],clip[2]/clip[3]};
    }
    @Override public void onDrawFrame(GL10 gl){gpu.draw(width,height);}
}
