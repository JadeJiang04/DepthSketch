package studio.depthsketch;

import android.graphics.*;
import java.util.Arrays;

/** Already prepared artwork: preserve its RGBA and canvas, without running segmentation. */
final class ImageMaterial {
    static Bitmap depthInput(Bitmap image){
        int[] pixels=new int[image.getWidth()*image.getHeight()];image.getPixels(pixels,0,image.getWidth(),0,0,image.getWidth(),image.getHeight());
        boolean visible=false,transparent=false;for(int pixel:pixels){int alpha=pixel>>>24;visible|=alpha!=0;transparent|=alpha!=255;}
        if(!visible)throw new IllegalArgumentException("这张素材完全透明，请选择有可见内容的图片");
        if(!transparent)return image;
        Bitmap opaque=Bitmap.createBitmap(image.getWidth(),image.getHeight(),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(opaque);canvas.drawColor(0xff808080);canvas.drawBitmap(image,0,0,null);return opaque;
    }
    static Scene.Cutout create(Bitmap image,DepthEstimator.Result estimated){
        int w=image.getWidth(),h=image.getHeight();int[] pixels=new int[w*h];image.getPixels(pixels,0,w,0,0,w,h);
        float[] depth=new float[w*h],inside=new float[w*h];int count=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int i=y*w+x;float value=Scene.sample(estimated.values,estimated.width,estimated.height,x/(float)Math.max(1,w-1),y/(float)Math.max(1,h-1));
            if(!Float.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("素材深度无效");depth[i]=value;if((pixels[i]>>>24)!=0)inside[count++]=value;
        }
        if(count==0)throw new IllegalArgumentException("素材没有可见内容");Arrays.sort(inside,0,count);float center=inside[count/2],lo=inside[(int)(count*.02)],hi=inside[Math.min(count-1,(int)(count*.98))];
        for(int i=0;i<depth.length;i++)depth[i]=(pixels[i]>>>24)==0?center:Math.max(lo,Math.min(hi,depth[i]));
        Scene.Cutout c=new Scene.Cutout();c.image=image;c.width=w;c.height=h;c.depth=depth;c.centerDepth=center;c.feather=0;return c;
    }
}
