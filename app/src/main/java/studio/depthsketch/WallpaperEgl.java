package studio.depthsketch;

import android.opengl.*;
import android.view.Surface;

/** EGL owns the wallpaper's system Surface directly; no off-screen screenshots or WebView. */
final class WallpaperEgl {
    private android.opengl.EGLDisplay display=EGL14.EGL_NO_DISPLAY;
    private android.opengl.EGLContext context=EGL14.EGL_NO_CONTEXT;
    private android.opengl.EGLSurface window=EGL14.EGL_NO_SURFACE;
    void create(Surface surface){
        display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);int[] version=new int[2];
        if(!EGL14.eglInitialize(display,version,0,version,1))throw new IllegalStateException("无法初始化壁纸 EGL");
        int[] attrs={EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_DEPTH_SIZE,16,EGL14.EGL_NONE};
        android.opengl.EGLConfig[] configs=new android.opengl.EGLConfig[1];int[] count=new int[1];
        if(!EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0)||count[0]==0)throw new IllegalStateException("壁纸 EGL 配置不可用");
        context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
        window=EGL14.eglCreateWindowSurface(display,configs[0],surface,new int[]{EGL14.EGL_NONE},0);
        if(context==EGL14.EGL_NO_CONTEXT||window==EGL14.EGL_NO_SURFACE||!EGL14.eglMakeCurrent(display,window,window,context))throw new IllegalStateException("无法创建壁纸绘制表面");
        EGL14.eglSwapInterval(display,1);
    }
    boolean swap(){return EGL14.eglSwapBuffers(display,window);}
    void close(){
        if(display!=EGL14.EGL_NO_DISPLAY){EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);if(window!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,window);if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,context);EGL14.eglTerminate(display);EGL14.eglReleaseThread();}
        display=EGL14.EGL_NO_DISPLAY;context=EGL14.EGL_NO_CONTEXT;window=EGL14.EGL_NO_SURFACE;
    }
}
