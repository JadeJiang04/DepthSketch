package studio.depthsketch;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import java.nio.ByteBuffer;

/** Canonical full-image composite. Call on the existing GL thread; preserves its framebuffer. */
final class SceneCapture {
    static Bitmap composite(Scene original){
        Scene scene=original.snapshot();scene.pitch=scene.yaw=scene.roll=0;scene.panX=scene.panY=0;scene.zoom=1;
        scene.wallpaper=true;scene.fillWallpaper=false;scene.wallpaperZoom=1;scene.showPhoto=true;scene.selected=scene.selectedCutout=-1;
        int width=scene.image.getWidth(),height=scene.image.getHeight();int[] oldFramebuffer=new int[1],oldRenderbuffer=new int[1],viewport=new int[4],ids=new int[3];
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING,oldFramebuffer,0);GLES20.glGetIntegerv(GLES20.GL_RENDERBUFFER_BINDING,oldRenderbuffer,0);GLES20.glGetIntegerv(GLES20.GL_VIEWPORT,viewport,0);
        GpuScene gpu=new GpuScene(scene);boolean initialized=false;
        try{
            int[] one=new int[1];GLES20.glGenFramebuffers(1,one,0);ids[0]=one[0];GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,ids[0]);
            GLES20.glGenTextures(1,one,0);ids[1]=one[0];GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,ids[1]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,width,height,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,ids[1],0);
            GLES20.glGenRenderbuffers(1,one,0);ids[2]=one[0];GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER,ids[2]);GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER,GLES20.GL_DEPTH_COMPONENT16,width,height);GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER,GLES20.GL_DEPTH_ATTACHMENT,GLES20.GL_RENDERBUFFER,ids[2]);
            if(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)!=GLES20.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("无法创建合成画布");
            GLES20.glViewport(0,0,width,height);gpu.init();initialized=true;gpu.draw(width,height);
            ByteBuffer rgba=ByteBuffer.allocateDirect(width*height*4);GLES20.glReadPixels(0,0,width,height,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,rgba);
            if(GLES20.glGetError()!=GLES20.GL_NO_ERROR)throw new IllegalStateException("无法读取合成图片");int[] pixels=new int[width*height];
            for(int y=0;y<height;y++)for(int x=0;x<width;x++){int p=(y*width+x)*4;pixels[(height-y-1)*width+x]=0xff000000|((rgba.get(p)&255)<<16)|((rgba.get(p+1)&255)<<8)|(rgba.get(p+2)&255);}
            return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);
        }finally{
            if(initialized)gpu.release();GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,oldFramebuffer[0]);GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER,oldRenderbuffer[0]);GLES20.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            GLES20.glDeleteFramebuffers(1,new int[]{ids[0]},0);GLES20.glDeleteTextures(1,new int[]{ids[1]},0);GLES20.glDeleteRenderbuffers(1,new int[]{ids[2]},0);
        }
    }
}
