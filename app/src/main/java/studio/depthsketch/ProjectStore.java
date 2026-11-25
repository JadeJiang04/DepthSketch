package studio.depthsketch;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.*;

/** A portable .dsketch ZIP contains the image, full float depth map and all editable strokes. */
public final class ProjectStore {
    /** Private handoff still uses format 2, with a 1 px base instead of duplicating the whole asset. */
    static Scene cutoutTransfer(Scene.Cutout cutout){
        Scene scene=new Scene();scene.setImage(Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888),new float[]{.5f},1,1);
        scene.cutouts.add(cutout);return scene;
    }
    private static final java.util.WeakHashMap<Bitmap,ImageAsset> images=new java.util.WeakHashMap<>();
    private static final class ImageAsset {
        byte[] png;
        long crc;
    }
    private static ImageAsset imageAsset(Bitmap image) {
        synchronized(images) { ImageAsset cached=images.get(image); if(cached!=null)return cached; }
        ByteArrayOutputStream encoded=new ByteArrayOutputStream();
        image.compress(Bitmap.CompressFormat.PNG,100,encoded);
        ImageAsset asset=new ImageAsset();asset.png=encoded.toByteArray();
        CRC32 crc=new CRC32();crc.update(asset.png);asset.crc=crc.getValue();
        synchronized(images) { images.put(image,asset); }
        return asset;
    }
    public static void write(OutputStream stream, Scene source) throws Exception {
        long started=android.os.SystemClock.elapsedRealtime();
        // Capture only references + scalar transforms under the scene lock. No PNG/ZIP/I/O under it.
        Scene scene=source.snapshot();
        {
            JSONObject root=new JSONObject(); root.put("format",2);
            root.put("depthWidth",scene.depthWidth); root.put("depthHeight",scene.depthHeight);
            root.put("relief",scene.relief); root.put("pitch",scene.pitch); root.put("yaw",scene.yaw);
            root.put("roll",scene.roll); root.put("zoom",scene.zoom); root.put("showPhoto",scene.showPhoto);
            root.put("viewPan",new JSONArray(new double[]{scene.panX,scene.panY}));
            JSONArray strokes=new JSONArray();
            for (Scene.Stroke s: scene.strokes) {
                JSONObject o=new JSONObject(); o.put("color",s.color); o.put("radius",s.radius);
                o.put("position",new JSONArray(new double[]{s.x,s.y,s.z}));
                o.put("rotation",new JSONArray(new double[]{s.rx,s.ry,s.rz}));
                o.put("centered",s.centered);
                JSONArray points=new JSONArray();
                for (float[] p:s.points) points.put(new JSONArray(new double[]{p[0],p[1],p[2]}));
                o.put("points",points); strokes.put(o);
            }
            root.put("strokes",strokes);
            JSONArray cutouts=new JSONArray();
            for(Scene.Cutout c:scene.cutouts){
                JSONObject o=new JSONObject();o.put("width",c.width);o.put("height",c.height);o.put("centerDepth",c.centerDepth);
                o.put("position",new JSONArray(new double[]{c.x,c.y,c.z}));o.put("rotation",new JSONArray(new double[]{c.rx,c.ry,c.rz}));
                o.put("scale",c.scale);o.put("relief",c.relief);cutouts.put(o);
                o.put("feather",c.feather);
            }
            root.put("cutouts",cutouts);
            try (ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(stream,65536))) {
                zip.setLevel(Deflater.BEST_SPEED);
                zip.putNextEntry(new ZipEntry("scene.json")); zip.write(root.toString().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
                ImageAsset image=imageAsset(scene.image);
                ZipEntry imageEntry=new ZipEntry("image.png");imageEntry.setMethod(ZipEntry.STORED);
                imageEntry.setSize(image.png.length);imageEntry.setCompressedSize(image.png.length);imageEntry.setCrc(image.crc);
                zip.putNextEntry(imageEntry);zip.write(image.png);zip.closeEntry();
                zip.putNextEntry(new ZipEntry("depth.bin"));
                writeDepth(zip,scene.depth);
                zip.closeEntry();
                for(int i=0;i<scene.cutouts.size();i++){
                    Scene.Cutout c=scene.cutouts.get(i);ImageAsset rgba=imageAsset(c.image);
                    ZipEntry png=new ZipEntry("objects/"+i+".png");png.setMethod(ZipEntry.STORED);png.setSize(rgba.png.length);png.setCompressedSize(rgba.png.length);png.setCrc(rgba.crc);
                    zip.putNextEntry(png);zip.write(rgba.png);zip.closeEntry();
                    zip.putNextEntry(new ZipEntry("objects/"+i+".depth"));writeDepth(zip,c.depth);zip.closeEntry();
                }
            }
        }
        android.util.Log.i("DepthSketchTiming","project_write ms="+(android.os.SystemClock.elapsedRealtime()-started)+" objects="+scene.cutouts.size());
    }
    public static Scene read(InputStream stream) throws Exception {
        byte[] metadata=null, pixels=null, depths=null;
        java.util.Map<String,byte[]> objects=new java.util.HashMap<>();
        try (ZipInputStream zip=new ZipInputStream(new BufferedInputStream(stream,65536))) {
            ZipEntry entry; int count=0,total=0;
            while ((entry=zip.getNextEntry())!=null) {
                if (++count>32) throw new IOException("项目文件无效");
                int limit=entry.getName().equals("scene.json")?16*1024*1024:32*1024*1024;
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                byte[] buffer=new byte[8192]; int n;
                while ((n=zip.read(buffer))!=-1) { if (bytes.size()+n>limit) throw new IOException("项目文件过大"); total+=n;if(total>128*1024*1024)throw new IOException("项目内容过大"); bytes.write(buffer,0,n); }
                if (entry.getName().equals("scene.json")) metadata=bytes.toByteArray();
                if (entry.getName().equals("image.png")) pixels=bytes.toByteArray();
                if (entry.getName().equals("depth.bin")) depths=bytes.toByteArray();
                if(entry.getName().matches("objects/[0-9]{1,2}\\.(png|depth)"))objects.put(entry.getName(),bytes.toByteArray());
            }
        }
        if (metadata==null||pixels==null||depths==null) throw new IOException("项目内容不完整");
        JSONObject root=new JSONObject(new String(metadata,StandardCharsets.UTF_8));
        if (root.getInt("format")!=1&&root.getInt("format")!=2) throw new IOException("不支持的项目版本");
        int w=root.getInt("depthWidth"), h=root.getInt("depthHeight");
        if (w<1||h<1||w>2048||h>2048||(long)w*h*4!=depths.length) throw new IOException("深度尺寸无效");
        BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(pixels,0,pixels.length,bounds);
        if (bounds.outWidth<1||bounds.outHeight<1||(long)bounds.outWidth*bounds.outHeight>2048L*2048) throw new IOException("图像尺寸无效");
        Bitmap image=BitmapFactory.decodeByteArray(pixels,0,pixels.length);
        if (image==null) throw new IOException("无法解码项目图像");
        float[] depth=readDepth(depths);
        Scene scene=new Scene(); scene.setImage(image,depth,w,h);
        scene.relief=finite(root,"relief",0,2); scene.pitch=finite(root,"pitch",-360,360); scene.yaw=finite(root,"yaw",-360,360);
        scene.roll=finite(root,"roll",-360,360); scene.zoom=finite(root,"zoom",.35f,5);
        JSONArray viewPan=root.optJSONArray("viewPan");if(viewPan!=null){scene.panX=component(viewPan,0,100);scene.panY=component(viewPan,1,100);}
        scene.showPhoto=root.optBoolean("showPhoto",true);
        JSONArray strokes=root.getJSONArray("strokes");
        if (strokes.length()>2000) throw new IOException("笔画数量过多");
        int totalPoints=0;
        for (int i=0;i<strokes.length();i++) {
            JSONObject o=strokes.getJSONObject(i); Scene.Stroke s=new Scene.Stroke();
            s.color=o.getInt("color"); s.radius=finite(o,"radius",.0001f,.2f); s.centered=o.getBoolean("centered");
            JSONArray position=o.getJSONArray("position"), rotation=o.getJSONArray("rotation");
            s.x=component(position,0,100); s.y=component(position,1,100); s.z=component(position,2,100);
            s.rx=component(rotation,0,360); s.ry=component(rotation,1,360); s.rz=component(rotation,2,360);
            JSONArray points=o.getJSONArray("points"); totalPoints+=points.length();
            if (totalPoints>200000) throw new IOException("笔画采样点过多");
            for (int j=0;j<points.length();j++) {
                JSONArray p=points.getJSONArray(j); s.points.add(new float[]{component(p,0,100),component(p,1,100),component(p,2,100)});
            }
            if (!s.points.isEmpty()) scene.strokes.add(s);
        }
        JSONArray layers=root.optJSONArray("cutouts");
        if(layers!=null){
            if(layers.length()>12)throw new IOException("抠图对象数量过多");
            for(int i=0;i<layers.length();i++){
                JSONObject o=layers.getJSONObject(i);byte[] png=objects.get("objects/"+i+".png"),data=objects.get("objects/"+i+".depth");
                if(png==null||data==null)throw new IOException("抠图对象内容缺失");
                Scene.Cutout c=new Scene.Cutout();c.width=o.getInt("width");c.height=o.getInt("height");
                if(c.width<1||c.height<1||c.width>2048||c.height>2048||(long)c.width*c.height*4!=data.length)throw new IOException("对象深度尺寸无效");
                BitmapFactory.Options b=new BitmapFactory.Options();b.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(png,0,png.length,b);
                if(b.outWidth!=c.width||b.outHeight!=c.height)throw new IOException("对象图像与深度未对齐");
                c.image=BitmapFactory.decodeByteArray(png,0,png.length);if(c.image==null)throw new IOException("对象图像无效");
                c.depth=readDepth(data);
                c.centerDepth=finite(o,"centerDepth",0,1);c.scale=finite(o,"scale",.05f,5);c.relief=finite(o,"relief",0,2);
                c.feather=o.has("feather")?finite(o,"feather",0,16):0;
                JSONArray position=o.getJSONArray("position"),rotation=o.getJSONArray("rotation");
                c.x=component(position,0,100);c.y=component(position,1,100);c.z=component(position,2,100);
                c.rx=component(rotation,0,360);c.ry=component(rotation,1,360);c.rz=component(rotation,2,360);scene.cutouts.add(c);
            }
        }
        return scene;
    }
    /** Preserve the v1/v2 big-endian floats, but submit 64 KB blocks to Deflater/JNI. */
    private static void writeDepth(OutputStream out,float[] depth)throws IOException {
        ByteBuffer block=ByteBuffer.allocate(65536).order(ByteOrder.BIG_ENDIAN);
        for(float value:depth){if(block.remaining()<4){out.write(block.array(),0,block.position());block.clear();}block.putFloat(value);}
        if(block.position()>0)out.write(block.array(),0,block.position());
    }
    private static float[] readDepth(byte[] bytes)throws IOException {
        float[] values=new float[bytes.length/4];ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asFloatBuffer().get(values);
        for(float value:values)if(!Float.isFinite(value)||value<0||value>1)throw new IOException("深度数据无效");
        return values;
    }
    private static float finite(JSONObject o,String key,float min,float max) throws Exception {
        float v=(float)o.getDouble(key); if (!Float.isFinite(v)||v<min||v>max) throw new IOException("项目参数无效: "+key); return v;
    }
    private static float component(JSONArray a,int i,float bound) throws Exception {
        float v=(float)a.getDouble(i); if (!Float.isFinite(v)||Math.abs(v)>bound) throw new IOException("笔画参数无效"); return v;
    }
}
