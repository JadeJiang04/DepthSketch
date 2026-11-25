package studio.depthsketch;

import android.content.*;
import android.graphics.BitmapFactory;
import java.io.*;

final class WallpaperFiles {
    static final String PREFS="wallpaper";
    private static final class CachedScene {
        final long modified,length;
        final String path;
        final Scene scene;
        CachedScene(File file,Scene scene){path=file.getAbsolutePath();modified=file.lastModified();length=file.length();this.scene=scene.snapshot();}
        boolean matches(File file){return path.equals(file.getAbsolutePath())&&file.exists()&&modified==file.lastModified()&&length==file.length();}
    }
    private static CachedScene previewCache,activeCache;
    static synchronized void publish(Context context,Scene scene)throws Exception {
        File temporary=new File(context.getFilesDir(),"wallpaper-preview.tmp"),target=new File(context.getFilesDir(),"wallpaper-preview.dsketch");
        ProjectStore.write(new FileOutputStream(temporary),scene);
        if(!temporary.renameTo(target))throw new IOException("无法保存壁纸作品");
        previewCache=new CachedScene(target,scene);
        context.getSharedPreferences(PREFS,0).edit().putLong("preview_updated",System.currentTimeMillis()).commit();
    }
    static synchronized Scene load(Context context)throws Exception {
        return load(context,false);
    }
    static synchronized void applyPreview(Context context)throws Exception {
        File preview=new File(context.getFilesDir(),"wallpaper-preview.dsketch"),temporary=new File(context.getFilesDir(),"wallpaper.tmp"),target=new File(context.getFilesDir(),"wallpaper.dsketch");
        try(InputStream in=new FileInputStream(preview);OutputStream out=new FileOutputStream(temporary)){byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);}
        if(!temporary.renameTo(target))throw new IOException("无法应用壁纸作品");
        activeCache=previewCache!=null&&previewCache.matches(preview)?new CachedScene(target,previewCache.scene):null;
        SharedPreferences prefs=context.getSharedPreferences(PREFS,0);prefs.edit().putLong("updated",prefs.getLong("updated",0)+1).commit();
    }
    static synchronized Scene load(Context context,boolean preview)throws Exception {
        File file=new File(context.getFilesDir(),preview?"wallpaper-preview.dsketch":"wallpaper.dsketch");Scene scene;
        CachedScene cache=preview?previewCache:activeCache;
        if(cache!=null&&cache.matches(file))scene=cache.scene.snapshot();
        else if(file.exists()){
            try(InputStream in=new FileInputStream(file)){scene=ProjectStore.read(in);}
            cache=new CachedScene(file,scene);if(preview)previewCache=cache;else activeCache=cache;
        }
        else{scene=new Scene();try(InputStream in=context.getAssets().open("sample.jpg")){scene.setImage(BitmapFactory.decodeStream(in),new float[]{.5f},1,1);}}
        scene.wallpaper=true;scene.showPhoto=true;scene.selected=scene.selectedCutout=-1;settings(context,scene);return scene;
    }
    static void settings(Context context,Scene scene){SharedPreferences p=context.getSharedPreferences(PREFS,0);
        if(p.getInt("framing_version",0)<2)p.edit().putBoolean("fill",false).putFloat("zoom",1).putInt("framing_version",2).apply();
        synchronized(scene){scene.fillWallpaper=p.getBoolean("fill",false);scene.wallpaperZoom=p.getFloat("zoom",1);}}
    static Intent previewIntent(Context context){return new Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,new ComponentName(context,DepthWallpaperService.class));}
}
