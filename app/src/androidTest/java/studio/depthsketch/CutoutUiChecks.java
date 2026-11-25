package studio.depthsketch;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.SystemClock;
import java.io.*;
import java.util.*;
import org.json.JSONObject;

/** Launch just the picker, accept a cutout, and inspect its private transfer. No editor is opened. */
final class CutoutUiChecks {
    static JSONObject run(Instrumentation test,File source)throws Exception {
        Context context=test.getTargetContext();Set<String> before=new HashSet<>();
        File[] entries=context.getCacheDir().listFiles();if(entries!=null)for(File file:entries)before.add(file.getName());
        JSONObject row=new JSONObject();long start=SystemClock.elapsedRealtime();
        Intent intent=new Intent(context,CutoutActivity.class).setData(Uri.fromFile(source)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Instrumentation.ActivityMonitor monitor=test.addMonitor(CutoutActivity.class.getName(),null,false);
        if(android.os.Build.VERSION.SDK_INT>=29)test.getUiAutomation().adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND");
        Activity launched;
        try{test.runOnMainSync(()->context.startActivity(intent));launched=test.waitForMonitorWithTimeout(monitor,15000);}
        finally{test.removeMonitor(monitor);if(android.os.Build.VERSION.SDK_INT>=29)test.getUiAutomation().dropShellPermissionIdentity();}
        if(launched==null){row.put("activity_rgba_depth_transfer","SKIPPED_SYSTEM_BACKGROUND_LAUNCH");return row;}
        CutoutActivity cut=(CutoutActivity)launched;
        try{
            until(()->!cut.busy&&cut.picker.image!=null,30000,"picker ready");row.put("ready_for_points_ms",SystemClock.elapsedRealtime()-start);
            test.runOnMainSync(()->{int w=cut.picker.image.getWidth(),h=cut.picker.image.getHeight();cut.picker.prompts.add(new float[]{w*.5f,h*.42f,1});cut.picker.prompts.add(new float[]{w*.5f,h*.22f,1});cut.picker.prompts.add(new float[]{w*.5f,h*.73f,1});cut.picker.prompts.add(new float[]{w*.25f,h*.69f,1});cut.refine();});
            until(()->!cut.busy,15000,"picker mask");check(cut.picker.overlay!=null,"visible mask missing");
            start=SystemClock.elapsedRealtime();test.runOnMainSync(cut::confirm);until(cut::isFinishing,15000,"accept cutout");row.put("confirm_depth_crop_save_ms",SystemClock.elapsedRealtime()-start);
            entries=context.getCacheDir().listFiles();File handoff=null;if(entries!=null)for(File file:entries)if(file.getName().matches("cutout-[0-9]+\\.dsketch")&&!before.contains(file.getName()))handoff=file;
            check(handoff!=null,"picker transfer missing");try(InputStream in=new FileInputStream(handoff)){Scene transfer=ProjectStore.read(in);check(transfer.depth.length==1&&transfer.cutouts.size()==1,"picker duplicated object into base");check(transfer.cutouts.get(0).depth.length>1000,"object depth missing");}finally{handoff.delete();}
            row.put("activity_rgba_depth_transfer","PASS");
        }finally{test.runOnMainSync(cut::finish);test.waitForIdleSync();}
        return row;
    }
    interface Condition{boolean ready();}
    private static void until(Condition condition,long timeout,String message){long end=SystemClock.elapsedRealtime()+timeout;while(!condition.ready()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(20);check(condition.ready(),"timeout: "+message);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
