package studio.depthsketch;

import java.util.*;
import android.graphics.Bitmap;

final class MaskCleanupChecks {
    static void run()throws Exception {
        int w=100,h=100;float[] mask=new float[w*h];Arrays.fill(mask,-10);
        for(int y=30;y<60;y++)for(int x=30;x<60;x++)mask[y*w+x]=3;
        mask[5*w+5]=3; // Remote noise would expand the crop to almost the entire image.
        mask[50*w+62]=3; // A detached strand two pixels from the retained body.
        mask[80*w+80]=3; // An intentionally selected tiny part.
        for(int y=10;y<14;y++)for(int x=70;x<74;x++)mask[y*w+x]=3; // Substantial accessory.
        mask[29*w+40]=-.2f;mask[8*w+8]=-.2f; // Edge alpha vs isolated soft alpha.
        mask[40*w+40]=-10; // Intentional hole must not be filled.
        MaskCleanup.Result result=MaskCleanup.clean(mask,w,h,Collections.singletonList(new float[]{80,80,1}));
        check(result.removedPixels==1&&result.removedComponents==1,"remove remote binary noise");
        check(mask[5*w+5]<-.5f&&mask[8*w+8]<-.5f,"remove noise alpha");
        check(mask[50*w+62]>0&&mask[80*w+80]>0&&mask[10*w+70]>0,"preserve strand, seed and accessory");
        check(mask[29*w+40]==-.2f&&mask[40*w+40]==-10,"preserve boundary and hole");
        float[] one=new float[w*h];Arrays.fill(one,-10);for(int y=30;y<60;y++)for(int x=30;x<60;x++)one[y*w+x]=3;one[5*w+5]=3;
        MaskCleanup.clean(one,w,h,Collections.emptyList());DepthEstimator.Result d=new DepthEstimator.Result();d.width=d.height=2;d.values=new float[]{.1f,.3f,.7f,.9f};
        Bitmap image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);image.eraseColor(0xff998877);Scene.Cutout c=ObjectSegmenter.crop(image,one,d);
        check(c.width==36&&c.height==36,"tight crop after remote noise removal");
        check(c.depth.length==c.width*c.height,"RGBA/depth alignment");check(c.image.getPixel(0,0)>>>24==0,"transparent crop padding");
        boolean rejected=false;one[0]=Float.NaN;try{MaskCleanup.clean(one,w,h,Collections.emptyList());}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"invalid mask rejection");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
