package studio.depthsketch;

import android.graphics.Bitmap;

/** Cached inner distance to transparency. Raw RGBA remains immutable and reversible. */
final class EdgeFeather {
    static byte[] distances(Bitmap image){
        int w=image.getWidth(),h=image.getHeight();int[] pixels=new int[w*h];image.getPixels(pixels,0,w,0,0,w,h);float[] d=new float[pixels.length];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int p=y*w+x;d[p]=(pixels[p]>>>24)<128?0:(x==0||y==0||x==w-1||y==h-1?1:16);}
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int p=y*w+x;float v=d[p];if(x>0)v=Math.min(v,d[p-1]+1);if(y>0){v=Math.min(v,d[p-w]+1);if(x>0)v=Math.min(v,d[p-w-1]+1.414214f);if(x+1<w)v=Math.min(v,d[p-w+1]+1.414214f);}d[p]=v;}
        for(int y=h-1;y>=0;y--)for(int x=w-1;x>=0;x--){int p=y*w+x;float v=d[p];if(x+1<w)v=Math.min(v,d[p+1]+1);if(y+1<h){v=Math.min(v,d[p+w]+1);if(x>0)v=Math.min(v,d[p+w-1]+1.414214f);if(x+1<w)v=Math.min(v,d[p+w+1]+1.414214f);}d[p]=v;}
        byte[] bytes=new byte[d.length];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Math.round(Math.min(16,d[i])*255/16);return bytes;
    }
}
