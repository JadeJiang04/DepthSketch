package studio.depthsketch;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.*;
import java.io.*;
import java.util.*;

/** Real-device diagnostics, isolated from editor documents and active/draft wallpaper files. */
final class CutoutLatencyChecks {
    private final Instrumentation test;
    private final Context context;
    private final File inputs,output,ioFolder;
    CutoutLatencyChecks(Instrumentation test){this.test=test;context=test.getTargetContext();inputs=context.getExternalFilesDir("latency-input");output=context.getExternalFilesDir("latency-checks");output.mkdirs();ioFolder=new File(context.getCacheDir(),"latency-io");ioFolder.mkdirs();}
    private void stage(String value){Bundle b=new Bundle();b.putString("stream",value+"\n");test.sendStatus(0,b);}
    private static long now(){return SystemClock.elapsedRealtime();}
    Bundle run(String label)throws Exception {
        JSONObject report=new JSONObject();report.put("label",label);report.put("device",android.os.Build.MODEL);report.put("android",android.os.Build.VERSION.RELEASE);report.put("io_location","app_private_cache");
        JSONArray cases=new JSONArray();report.put("cases",cases);
        if(!label.equals("baseline")){
            File saved=new File(context.getFilesDir(),"autosave.dsketch");
            if(saved.exists()){
                long readStart=now();Scene savedScene=ProjectStore.read(new FileInputStream(saved));
                report.put("existing_user_project",new JSONObject().put("read_ms",now()-readStart).put("cutouts",savedScene.cutouts.size()).put("strokes",savedScene.strokes.size()).put("read_only","PASS"));
            }
        }
        long t=now();Bitmap background=ImageFiles.decode(context,Uri.fromFile(new File(inputs,"1.jpg")));
        DepthEstimator.Result backgroundDepth;try(DepthEstimator estimator=new DepthEstimator(context)){backgroundDepth=estimator.estimate(background);}
        report.put("background_prepare_ms",now()-t);
        for(String name:new String[]{"character1.jpg","charater2.jpg"}){
            stage(label+" "+name+" decode");JSONObject row=new JSONObject();row.put("source",name);cases.put(row);
            t=now();Bitmap image=ImageFiles.decode(context,Uri.fromFile(new File(inputs,name)));row.put("decode_ms",now()-t);row.put("width",image.getWidth());row.put("height",image.getHeight());
            stage(name+" depth");DepthEstimator.Result depth;t=now();try(DepthEstimator estimator=new DepthEstimator(context)){depth=estimator.estimate(image);}row.put("depth_ms",now()-t);
            float[] mask;
            try(ObjectSegmenter segmenter=new ObjectSegmenter(context)){
                stage(name+" SAM prepare");t=now();segmenter.prepare(image);row.put("sam_prepare_ms",now()-t);
                List<float[]> prompts=new ArrayList<>();prompts.add(new float[]{image.getWidth()*.5f,image.getHeight()*.42f,1});
                stage(name+" first point");t=now();mask=segmenter.segment(prompts);row.put("first_point_ms",now()-t);row.put("first_mask",stats(mask,image.getWidth(),image.getHeight()));
                // Same reproducible points before/after; broad parts, including the second role's tail.
                prompts.add(new float[]{image.getWidth()*.50f,image.getHeight()*.22f,1});
                prompts.add(new float[]{image.getWidth()*.50f,image.getHeight()*.73f,1});
                if(name.equals("charater2.jpg"))prompts.add(new float[]{image.getWidth()*.25f,image.getHeight()*.69f,1});
                t=now();mask=segmenter.segment(prompts);row.put("refine_ms",now()-t);row.put("mask",stats(mask,image.getWidth(),image.getHeight()));
            }
            File maskFile=new File(output,label+"-"+name+".mask");try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(maskFile),65536))){out.writeInt(image.getWidth());out.writeInt(image.getHeight());for(float v:mask)out.writeFloat(v);}
            stage(name+" crop");t=now();Scene.Cutout cutout=ObjectSegmenter.crop(image,mask,depth);row.put("crop_ms",now()-t);row.put("crop_width",cutout.width);row.put("crop_height",cutout.height);
            try(OutputStream out=new FileOutputStream(new File(output,label+"-"+name+".png"))){cutout.image.compress(Bitmap.CompressFormat.PNG,100,out);}
            Scene transfer;
            if(label.equals("baseline")){transfer=new Scene();transfer.setImage(cutout.image,cutout.depth,cutout.width,cutout.height);transfer.cutouts.add(cutout);}
            else transfer=ProjectStore.cutoutTransfer(cutout);
            File file=new File(ioFolder,label+"-transfer.dsketch");stage(name+" transfer write");t=now();ProjectStore.write(new FileOutputStream(file),transfer);row.put("transfer_write_ms",now()-t);row.put("transfer_bytes",file.length());
            t=now();Scene restored=ProjectStore.read(new FileInputStream(file));row.put("transfer_read_ms",now()-t);check(restored.cutouts.get(0).depth.length==cutout.depth.length,"transfer depth");
            Scene scene=new Scene();scene.setImage(background,backgroundDepth.values,backgroundDepth.width,backgroundDepth.height);scene.cutouts.add(cutout);
            file=new File(ioFolder,label+"-"+name+".dsketch");stage(name+" wallpaper snapshot write");t=now();ProjectStore.write(new FileOutputStream(file),scene);row.put("wallpaper_write_ms",now()-t);row.put("wallpaper_bytes",file.length());
            t=now();ProjectStore.write(new FileOutputStream(file),scene);row.put("wallpaper_repeat_write_ms",now()-t);
            stage(name+" wallpaper read");t=now();Scene loaded=ProjectStore.read(new FileInputStream(file));row.put("wallpaper_read_ms",now()-t);
            check(loaded.cutouts.size()==1,"object roundtrip");for(int i=0;i<cutout.depth.length;i+=71)check(cutout.depth[i]==loaded.cutouts.get(0).depth[i],"depth loss");
            if(!label.equals("baseline")){
                File old=new File(ioFolder,"baseline-"+name+".dsketch");if(old.exists()){Scene legacy=ProjectStore.read(new FileInputStream(old));check(legacy.cutouts.size()==1,"legacy object lost");row.put("legacy_v2_read","PASS");}
                row.put("wallpaper_pipeline",WallpaperSnapshotChecks.run(context,scene));
            }
            row.put("java_heap_mb",(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1048576);stage(row.toString());
            try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(output,label+".json")),"UTF-8")){out.write(report.toString(2));}
        }
        if(!label.equals("baseline")){MaskCleanupChecks.run();report.put("cleanup_regression","PASS");
            report.put("picker_activity",CutoutUiChecks.run(test,new File(inputs,"charater2.jpg")));
            try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(output,label+".json")),"UTF-8")){out.write(report.toString(2));}}
        Bundle result=new Bundle();result.putString("result","PASS");result.putString("report",report.toString());return result;
    }
    static JSONObject stats(float[] mask,int w,int h)throws Exception {
        int[] labels=new int[mask.length],queue=new int[mask.length];int components=0,small=0,total=0,left=w,top=h,right=-1,bottom=-1;JSONArray largest=new JSONArray();List<Integer> sizes=new ArrayList<>();
        for(int p=0;p<mask.length;p++)if(mask[p]>0){total++;left=Math.min(left,p%w);right=Math.max(right,p%w);top=Math.min(top,p/w);bottom=Math.max(bottom,p/w);if(labels[p]!=0)continue;
            components++;int head=0,tail=1;queue[0]=p;labels[p]=components;
            while(head<tail){int q=queue[head++],x=q%w,y=q/w;for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++){int n=yy*w+xx;if(labels[n]==0&&mask[n]>0){labels[n]=components;queue[tail++]=n;}}}
            sizes.add(tail);if(tail<=64)small++;
        }
        sizes.sort(Collections.reverseOrder());for(int i=0;i<Math.min(12,sizes.size());i++)largest.put(sizes.get(i));
        return new JSONObject().put("foreground",total).put("components",components).put("components_at_most_64px",small).put("largest_components",largest).put("bbox",new JSONArray(new int[]{left,top,right,bottom})).put("bbox_area",Math.max(0,right-left+1)*Math.max(0,bottom-top+1));
    }
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
