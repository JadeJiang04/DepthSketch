package studio.depthsketch;

import android.content.*;
import android.os.SystemClock;
import java.io.*;
import org.json.JSONObject;

/** Exercise real publish/load/apply code in a private diagnostic directory and preferences. */
final class WallpaperSnapshotChecks {
    static JSONObject run(Context base,Scene source)throws Exception {
        Context context=new ContextWrapper(base){
            @Override public File getFilesDir(){File folder=new File(base.getCacheDir(),"latency-wallpaper");folder.mkdirs();return folder;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return base.getSharedPreferences("latency_test_"+name,mode);}
        };
        JSONObject row=new JSONObject();long t=SystemClock.elapsedRealtime();WallpaperFiles.publish(context,source);row.put("publish_ms",SystemClock.elapsedRealtime()-t);
        t=SystemClock.elapsedRealtime();Scene preview=WallpaperFiles.load(context,true);row.put("warm_preview_load_ms",SystemClock.elapsedRealtime()-t);
        check(preview.image==source.image&&preview.cutouts.get(0).image==source.cutouts.get(0).image,"preview must reuse immutable assets");
        float z=source.cutouts.get(0).z;preview.cutouts.get(0).z+=1;preview.pitch=12;
        Scene second=WallpaperFiles.load(context,true);check(second.cutouts.get(0).z==z&&second.pitch==source.pitch,"preview transforms leaked to cache");
        WallpaperFiles.applyPreview(context);Scene active=WallpaperFiles.load(context,false);check(active.cutouts.get(0).z==z,"active handoff lost transform");
        Scene edit=source.snapshot();edit.cutouts.get(0).z+=.2f;WallpaperFiles.publish(context,edit);
        check(WallpaperFiles.load(context,false).cutouts.get(0).z==z,"draft changed active wallpaper");
        File file=new File(context.getFilesDir(),"wallpaper-preview.dsketch");check(file.setLastModified(file.lastModified()+2000),"cache invalidation setup");
        t=SystemClock.elapsedRealtime();Scene cold=WallpaperFiles.load(context,true);row.put("cold_preview_load_ms",SystemClock.elapsedRealtime()-t);
        check(cold.image!=source.image&&cold.cutouts.get(0).z==edit.cutouts.get(0).z,"stale draft cache");row.put("asset_reuse_transform_isolation_active_draft","PASS");return row;
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
