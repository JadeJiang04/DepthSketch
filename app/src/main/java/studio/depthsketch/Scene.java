package studio.depthsketch;

import android.graphics.Bitmap;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

/** World units: image height = 2; positive Z points towards the original viewer. */
public final class Scene {
    public Bitmap image;
    public float[] depth;
    public int depthWidth, depthHeight;
    public float aspect = 1f, relief = .8f;
    public float pitch, yaw, roll, zoom = 1f;
    public float panX,panY;
    public boolean showPhoto = true, surfaceMode = true;
    public int color = 0xffb9ee8a;
    public float brushDp = 5f;
    public int selected = -1;
    public int selectedCutout = -1;
    public boolean wallpaper,fillWallpaper;
    public float wallpaperZoom=1;
    public long revision, imageRevision, geometryRevision;
    public final List<Stroke> strokes = new ArrayList<>();
    public final List<Cutout> cutouts = new ArrayList<>();

    public static final class Cutout {
        public Bitmap image;
        public float[] depth;
        public int width,height;
        public float centerDepth=.5f,x,y,z=.6f,scale=.55f,rx,ry,rz,relief=.65f;
        public float feather=1.5f;
        Object assetKey=new Object();
        public float aspect(){return (float)image.getWidth()/image.getHeight();}
        public Cutout copy(){
            Cutout c=new Cutout();c.image=image;c.depth=depth;c.width=width;c.height=height;c.centerDepth=centerDepth;
            c.x=x;c.y=y;c.z=z;c.scale=scale;c.rx=rx;c.ry=ry;c.rz=rz;c.relief=relief;c.feather=feather;c.assetKey=assetKey;return c;
        }
        float depthAt(float u,float v){return sample(depth,width,height,u,v);}
    }
    static float sample(float[] data,int w,int h,float u,float v){
        float px=Math.max(0,Math.min(1,u))*(w-1),py=Math.max(0,Math.min(1,v))*(h-1);
        int x=(int)px,y=(int)py,nx=Math.min(x+1,w-1),ny=Math.min(y+1,h-1);float a=px-x,b=py-y;
        return (1-b)*((1-a)*data[y*w+x]+a*data[y*w+nx])+b*((1-a)*data[ny*w+x]+a*data[ny*w+nx]);
    }

    public static final class Stroke {
        public List<float[]> points = new ArrayList<>();
        Object geometryKey = new Object();
        public int color;
        public float radius;
        public float x, y, z, rx, ry, rz;
        public boolean centered;
        public Stroke copy() {
            Stroke s = new Stroke();
            s.color=color; s.radius=radius; s.x=x; s.y=y; s.z=z;
            s.rx=rx; s.ry=ry; s.rz=rz; s.centered=centered;
            if (centered) { s.points=points; s.geometryKey=geometryKey; }
            else for (float[] p: points) s.points.add(p.clone());
            return s;
        }
        public void center() {
            if (centered || points.isEmpty()) return;
            for (float[] p: points) { x+=p[0]; y+=p[1]; z+=p[2]; }
            x/=points.size(); y/=points.size(); z/=points.size();
            for (float[] p: points) { p[0]-=x; p[1]-=y; p[2]-=z; }
            centered=true;
            points=Collections.unmodifiableList(points);
            geometryKey=new Object();
        }
    }

    public float surface(float x, float y) {
        if (depth==null) return 0;
        float u=Math.max(0,Math.min(1,x/(2*aspect)+.5f))*(depthWidth-1);
        float v=Math.max(0,Math.min(1,.5f-y/2))*(depthHeight-1);
        int ix=(int)u, iy=(int)v, jx=Math.min(ix+1,depthWidth-1), jy=Math.min(iy+1,depthHeight-1);
        float a=u-ix, b=v-iy;
        float d=(1-b)*((1-a)*depth[iy*depthWidth+ix]+a*depth[iy*depthWidth+jx])
            +b*((1-a)*depth[jy*depthWidth+ix]+a*depth[jy*depthWidth+jx]);
        return (d-.5f)*relief;
    }
    public void home() { pitch=yaw=roll=0; zoom=1;panX=panY=0; revision++; }
    public void changed() { revision++; }
    /** Bitmap, depth and completed stroke coordinates are immutable after publication. */
    public Scene snapshot() {
        synchronized(this) {
            Scene copy=new Scene();copy.image=image;copy.depth=depth;
            copy.depthWidth=depthWidth;copy.depthHeight=depthHeight;copy.aspect=aspect;copy.relief=relief;
            copy.pitch=pitch;copy.yaw=yaw;copy.roll=roll;copy.zoom=zoom;copy.showPhoto=showPhoto;
            copy.panX=panX;copy.panY=panY;
            copy.selected=selected;copy.revision=revision;copy.imageRevision=imageRevision;copy.geometryRevision=geometryRevision;
            copy.selectedCutout=selectedCutout;copy.wallpaper=wallpaper;copy.fillWallpaper=fillWallpaper;copy.wallpaperZoom=wallpaperZoom;
            for(Stroke stroke:strokes)copy.strokes.add(stroke.copy());
            for(Cutout c:cutouts)copy.cutouts.add(c.copy());
            return copy;
        }
    }
    public void setImage(Bitmap bitmap, float[] values, int w, int h) {
        image=bitmap; depth=values; depthWidth=w; depthHeight=h;
        aspect=(float)bitmap.getWidth()/bitmap.getHeight();
        strokes.clear();cutouts.clear(); selected=selectedCutout=-1; home(); imageRevision++; geometryRevision++;
    }
}
