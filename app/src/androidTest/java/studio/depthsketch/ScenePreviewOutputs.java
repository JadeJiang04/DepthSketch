package studio.depthsketch;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.opengl.*;
import android.os.Bundle;
import java.io.*;
import java.nio.*;
import java.util.*;
import org.json.*;

/** Exports real production GL scene renders, never changes the user's editor or wallpaper. */
final class ScenePreviewOutputs {
    private final Instrumentation test;
    private final Context context;
    private final File inputs,outputs;
    ScenePreviewOutputs(Instrumentation test){this.test=test;context=test.getTargetContext();inputs=context.getExternalFilesDir("latency-input");outputs=context.getExternalFilesDir("scene-previews-0.3.2");outputs.mkdirs();}
    private void status(String text){Bundle b=new Bundle();b.putString("stream",text+"\n");test.sendStatus(0,b);}
    Bundle run()throws Exception {
        JSONObject meshChecks=PhotoMeshChecks.run();
        Scene saved;try(InputStream in=new FileInputStream(new File(context.getCacheDir(),"latency-io/optimized-character1.jpg.dsketch"))){saved=ProjectStore.read(in);}
        Scene.Cutout source=saved.cutouts.get(0);JSONArray report=new JSONArray();
        // Normalized source-image placements derived from the supplied blue sketches.
        float[][] poses={{.565f,.528f,.122f,.995f},{.393f,.416f,.224f,.45f},{.688f,.578f,.150f,.50f}};
        String[] names={"1-full-body","2-upper-body","3-behind-column"};
        for(int index=0;index<3;index++){
            status("render "+names[index]);Bitmap image=ImageFiles.decode(context,Uri.fromFile(new File(inputs,(index+1)+".jpg")));
            DepthEstimator.Result depth;
            if(index==0){depth=new DepthEstimator.Result();depth.values=saved.depth;depth.width=saved.depthWidth;depth.height=saved.depthHeight;}
            else try(DepthEstimator estimator=new DepthEstimator(context)){depth=estimator.estimate(image);}
            Scene scene=new Scene();scene.setImage(image,depth.values,depth.width,depth.height);scene.wallpaper=true;scene.fillWallpaper=true;
            scene.zoom=1; // Wallpaper framing is now independent of editor navigation and has no hidden overscan.
            Scene.Cutout c=source.copy();c.x=(poses[index][0]-.5f)*2*scene.aspect;c.y=1-2*poses[index][1];c.scale=poses[index][2];
            c.z=chooseDepth(scene,c,poses[index][3]);scene.cutouts.add(c);
            try(OutputStream out=new FileOutputStream(new File(outputs,names[index]+".dsketch"))){ProjectStore.write(out,scene);}
            JSONObject row=new JSONObject();row.put("case",names[index]);row.put("x",c.x);row.put("y",c.y);row.put("scale",c.scale);row.put("z",c.z);row.put("depth_quantile",poses[index][3]);
            row.put("depth_samples",new JSONArray(new double[]{scene.surface(c.x-.04f,c.y),scene.surface(c.x,c.y),scene.surface(c.x+.04f,c.y),scene.surface(c.x,c.y-.12f),scene.surface(c.x,c.y+.12f)}));
            Bitmap composite;
            try(Pbuffer egl=new Pbuffer(960,1280)){
                GpuScene gpu=new GpuScene(scene);gpu.init();gpu.draw(960,1280);saveFrame(new File(outputs,names[index]+".png"),960,1280);
                scene.pitch=2;scene.yaw=-4;gpu.draw(960,1280);saveFrame(new File(outputs,names[index]+"-tilt.png"),960,1280);
                scene.pitch=-6;scene.yaw=6;gpu.draw(960,1280);saveFrame(new File(outputs,names[index]+"-tilt-max.png"),960,1280);
                row.put("photo_vertices",gpu.photoVertexCount);row.put("photo_triangles",gpu.photoTriangleCount);
                long uploads=gpu.photoUploads;long[] times=new long[40];
                for(int f=0;f<times.length;f++){scene.pitch=(float)(2*Math.sin(f*.2));scene.yaw=(float)(4*Math.cos(f*.2));long begin=System.nanoTime();gpu.draw(960,1280);GLES20.glFinish();times[f]=System.nanoTime()-begin;}
                Arrays.sort(times);row.put("cached_draw_median_ms",times[times.length/2]/1e6);row.put("cached_draw_p95_ms",times[37]/1e6);row.put("camera_changed_without_mesh_upload",gpu.photoUploads==uploads);
                scene.pitch=scene.yaw=0;c.feather=3;gpu.draw(960,1280);saveFrame(new File(outputs,names[index]+"-feather.png"),960,1280);c.feather=source.feather;
                composite=SceneCapture.composite(scene);
                int error=GLES20.glGetError();if(error!=GLES20.GL_NO_ERROR)throw new IllegalStateException("GL error "+error);
            }
            DepthEstimator.Result combinedDepth;try(DepthEstimator estimator=new DepthEstimator(context)){combinedDepth=estimator.estimate(composite);}
            Scene combined=new Scene();combined.setImage(composite,combinedDepth.values,combinedDepth.width,combinedDepth.height);combined.relief=scene.relief;combined.wallpaper=true;combined.fillWallpaper=true;
            ProjectStore.write(new FileOutputStream(new File(outputs,names[index]+"-reestimated.dsketch")),combined);row.put("composite_reestimate_ms",combinedDepth.millis);row.put("reestimated_layers",combined.cutouts.size()+combined.strokes.size());
            try(Pbuffer egl=new Pbuffer(960,1280)){GpuScene gpu=new GpuScene(combined);gpu.init();gpu.draw(960,1280);saveFrame(new File(outputs,names[index]+"-reestimated.png"),960,1280);combined.pitch=2;combined.yaw=-4;gpu.draw(960,1280);saveFrame(new File(outputs,names[index]+"-reestimated-tilt.png"),960,1280);}
            report.put(row);status(row.toString());
        }
        try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(outputs,"render-settings.json")),"UTF-8")){out.write(new JSONObject().put("renderer","production GpuScene / OpenGL ES 2 / 0.3.2 continuous photo mesh").put("mesh_checks",meshChecks).put("placement","manual XY/scale from the user's sketches; Z set from relative-depth quantile, not a new segmentation mask").put("cases",report).toString(2));}
        Bundle result=new Bundle();result.putString("result","PASS");result.putString("output",outputs.getAbsolutePath());return result;
    }
    private static float chooseDepth(Scene scene,Scene.Cutout c,float fraction){
        int[] pixels=new int[c.width*c.height];c.image.getPixels(pixels,0,c.width,0,0,c.width,c.height);
        float[] required=new float[pixels.length];int count=0;
        for(int y=0;y<c.height;y+=2)for(int x=0;x<c.width;x+=2)if((pixels[y*c.width+x]>>>24)>128){
            float u=x/(float)(c.width-1),v=y/(float)(c.height-1);
            float wx=c.x+(u-.5f)*2*c.aspect()*c.scale,wy=c.y+(.5f-v)*2*c.scale;
            required[count++]=scene.surface(wx,wy)-(c.depthAt(u,v)-c.centerDepth)*c.scale*c.relief;
        }
        Arrays.sort(required,0,count);return required[Math.min(count-1,(int)(count*fraction))]+.0005f;
    }
    private static void saveFrame(File file,int width,int height)throws IOException {
        GLES20.glFinish();ByteBuffer rgba=ByteBuffer.allocateDirect(width*height*4);GLES20.glReadPixels(0,0,width,height,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,rgba);
        int[] pixels=new int[width*height];for(int y=0;y<height;y++)for(int x=0;x<width;x++){int p=(y*width+x)*4;pixels[(height-y-1)*width+x]=0xff000000|((rgba.get(p)&255)<<16)|((rgba.get(p+1)&255)<<8)|(rgba.get(p+2)&255);}
        Bitmap image=Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);try(OutputStream out=new FileOutputStream(file)){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
    }
    static final class Pbuffer implements AutoCloseable {
        private final android.opengl.EGLDisplay display;
        private final android.opengl.EGLContext context;
        private final android.opengl.EGLSurface surface;
        Pbuffer(int width,int height){
            display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);int[] version=new int[2];if(!EGL14.eglInitialize(display,version,0,version,1))throw new IllegalStateException("EGL init");
            int[] attrs={EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_DEPTH_SIZE,16,EGL14.EGL_NONE};
            android.opengl.EGLConfig[] config=new android.opengl.EGLConfig[1];int[] n=new int[1];if(!EGL14.eglChooseConfig(display,attrs,0,config,0,1,n,0)||n[0]==0)throw new IllegalStateException("EGL config");
            context=EGL14.eglCreateContext(display,config[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
            surface=EGL14.eglCreatePbufferSurface(display,config[0],new int[]{EGL14.EGL_WIDTH,width,EGL14.EGL_HEIGHT,height,EGL14.EGL_NONE},0);
            if(!EGL14.eglMakeCurrent(display,surface,surface,context))throw new IllegalStateException("EGL current");GLES20.glViewport(0,0,width,height);
        }
        public void close(){EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);EGL14.eglDestroySurface(display,surface);EGL14.eglDestroyContext(display,context);EGL14.eglTerminate(display);EGL14.eglReleaseThread();}
    }
}
