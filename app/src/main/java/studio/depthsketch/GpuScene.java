package studio.depthsketch;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.opengl.*;
import java.nio.*;
import java.util.*;

/** Retains actual freehand stroke meshes on the GPU. All GL state belongs to the GL thread. */
final class GpuScene {
    private final Scene scene;
    private int program,texture,meshVbo,meshEbo,gridVbo,meshIndices;
    private int aPosition,aOffset,aUV,uMatrix,uColor,uTextured,uTexture,uRadius;
    private int uFeather,uDistance;
    private long photoVersion=-1,meshVersion=-1,frameNumber;
    private final float[] projection=new float[16],world=new float[16],camera=new float[16];
    private final IdentityHashMap<Object,StrokeBuffer> buffers=new IdentityHashMap<>();
    private final IdentityHashMap<Object,CutoutMesh> cutoutBuffers=new IdentityHashMap<>();
    private final ArrayList<DrawCutout> cutoutPool=new ArrayList<>();
    private final ArrayList<DrawStroke> pool=new ArrayList<>();
    private final Frame frame=new Frame();
    long geometryUploads,geometryUploadBytes;
    int photoVertexCount,photoTriangleCount;
    long photoUploads;
    private static final class DrawStroke {
        Object key;int color,count;float radius;float[] changedPoints;
        final float[] object=new float[16],mvp=new float[16];
    }
    private static final class Frame {
        Bitmap image;float aspect,pitch,yaw,roll,zoom,panX,panY;boolean photo;
        int count,selected,cutoutCount,selectedCutout;long imageRevision,geometryRevision;Scene meshSource;
        boolean wallpaper,fill;float wallpaperZoom;
    }
    private static final class DrawCutout {
        Scene.Cutout source;
        final float[] object=new float[16],mvp=new float[16];
    }
    GpuScene(Scene scene){this.scene=scene;}
    private static FloatBuffer floats(float[] values){
        FloatBuffer b=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();b.put(values).flip();return b;
    }
    private void capture(){
        synchronized(scene){
            frame.image=scene.image;frame.aspect=scene.aspect;frame.pitch=scene.pitch;frame.yaw=scene.yaw;
            frame.roll=scene.roll;frame.zoom=scene.zoom;frame.photo=scene.showPhoto;
            frame.panX=scene.panX;frame.panY=scene.panY;
            frame.imageRevision=scene.imageRevision;frame.geometryRevision=scene.geometryRevision;
            frame.wallpaper=scene.wallpaper;frame.fill=scene.fillWallpaper;frame.wallpaperZoom=scene.wallpaperZoom;
            frame.cutoutCount=scene.cutouts.size();frame.selectedCutout=scene.selectedCutout;
            for(int i=0;i<frame.cutoutCount;i++){
                if(cutoutPool.size()<=i)cutoutPool.add(new DrawCutout());
                DrawCutout draw=cutoutPool.get(i);draw.source=scene.cutouts.get(i).copy();CutoutMesh.matrix(draw.object,draw.source);
            }
            for(int i=frame.cutoutCount;i<cutoutPool.size();i++)cutoutPool.get(i).source=null;
            frame.selected=-1;frame.count=0;
            if(meshVersion!=scene.geometryRevision){
                Scene s=new Scene();s.depth=scene.depth;s.depthWidth=scene.depthWidth;s.depthHeight=scene.depthHeight;
                s.aspect=scene.aspect;s.relief=scene.relief;frame.meshSource=s;
            }
            for(int i=0;i<scene.strokes.size();i++){
                Scene.Stroke s=scene.strokes.get(i);if(s.points.isEmpty())continue;
                if(pool.size()<=frame.count)pool.add(new DrawStroke());DrawStroke draw=pool.get(frame.count);
                if(i==scene.selected)frame.selected=frame.count;
                frame.count++;draw.key=s.geometryKey;draw.color=s.color;draw.count=s.points.size();draw.radius=s.radius;
                Matrix.setIdentityM(draw.object,0);Matrix.translateM(draw.object,0,s.x,s.y,s.z);
                Matrix.rotateM(draw.object,0,s.rz,0,0,1);Matrix.rotateM(draw.object,0,s.ry,0,1,0);Matrix.rotateM(draw.object,0,s.rx,1,0,0);
                StrokeBuffer gpu=buffers.get(draw.key);
                if(gpu==null||gpu.pointCount!=draw.count||gpu.radius!=draw.radius){
                    if(draw.changedPoints==null||draw.changedPoints.length!=draw.count*3)draw.changedPoints=new float[draw.count*3];
                    int k=0;for(float[] p:s.points){draw.changedPoints[k++]=p[0];draw.changedPoints[k++]=p[1];draw.changedPoints[k++]=p[2];}
                }else draw.changedPoints=null;
            }
        }
    }
    void init(){
        String vs="uniform mat4 matrix; uniform float radiusScale; attribute vec3 position; attribute vec3 offset; attribute vec2 uv; varying vec2 tex; void main(){gl_Position=matrix*vec4(position+offset*radiusScale,1.0);tex=uv;}";
        String fs="precision mediump float; uniform vec4 color; uniform sampler2D image; uniform sampler2D edgeDistance; uniform float feather; uniform bool textured; varying vec2 tex; void main(){if(textured){vec4 p=texture2D(image,tex);if(p.a<0.04)discard;p.rgb/=max(p.a,0.001);if(feather>0.0)p.a*=smoothstep(0.0,feather,texture2D(edgeDistance,tex).r*16.0);if(p.a<0.04)discard;gl_FragColor=p*color;}else{gl_FragColor=color;}}";
        program=GLES20.glCreateProgram();GLES20.glAttachShader(program,shader(GLES20.GL_VERTEX_SHADER,vs));GLES20.glAttachShader(program,shader(GLES20.GL_FRAGMENT_SHADER,fs));GLES20.glLinkProgram(program);
        int[] ok=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0);if(ok[0]==0)throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
        aPosition=GLES20.glGetAttribLocation(program,"position");aOffset=GLES20.glGetAttribLocation(program,"offset");aUV=GLES20.glGetAttribLocation(program,"uv");
        uMatrix=GLES20.glGetUniformLocation(program,"matrix");uColor=GLES20.glGetUniformLocation(program,"color");uTextured=GLES20.glGetUniformLocation(program,"textured");uTexture=GLES20.glGetUniformLocation(program,"image");uRadius=GLES20.glGetUniformLocation(program,"radiusScale");
        uFeather=GLES20.glGetUniformLocation(program,"feather");uDistance=GLES20.glGetUniformLocation(program,"edgeDistance");GLES20.glUseProgram(program);GLES20.glUniform1i(uDistance,1);
        int[] ids=new int[1];GLES20.glGenTextures(1,ids,0);texture=ids[0];GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        ids=new int[3];GLES20.glGenBuffers(3,ids,0);meshVbo=ids[0];meshEbo=ids[1];gridVbo=ids[2];
        float[] lines=new float[33*12];int n=0;
        for(int i=-16;i<=16;i++){float k=i*.25f;float[] line={-4,k,-.65f,4,k,-.65f,k,-4,-.65f,k,4,-.65f};System.arraycopy(line,0,lines,n,12);n+=12;}
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,gridVbo);GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,lines.length*4,floats(lines),GLES20.GL_STATIC_DRAW);GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,0);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);GLES20.glDepthFunc(GLES20.GL_LEQUAL);GLES20.glEnable(GLES20.GL_BLEND);GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA,GLES20.GL_ONE_MINUS_SRC_ALPHA);
        buffers.clear();cutoutBuffers.clear();photoVersion=meshVersion=-1;
    }
    private static int shader(int type,String source){
        int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,source);GLES20.glCompileShader(s);int[] ok=new int[1];GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);if(ok[0]==0)throw new IllegalStateException(GLES20.glGetShaderInfoLog(s));return s;
    }
    void draw(int width,int height){
        capture(); // No scene lock during uploads, mesh generation, draw calls or the driver wait.
        frameNumber++;GLES20.glClearColor(.065f,.082f,.071f,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);GLES20.glUseProgram(program);
        float halfH=1.28f,halfW=halfH*width/height;if(halfW<frame.aspect*1.18f){halfW=frame.aspect*1.18f;halfH=halfW*height/width;}
        if(frame.wallpaper){halfH=frame.fill?Math.min(1,frame.aspect*height/width):Math.max(1,frame.aspect*height/width);halfW=halfH*width/height;}
        Matrix.orthoM(projection,0,-halfW,halfW,-halfH,halfH,-20,20);Matrix.setIdentityM(world,0);
        if(!frame.wallpaper)Matrix.translateM(world,0,frame.panX,frame.panY,0);
        float displayZoom=frame.wallpaper?frame.wallpaperZoom:frame.zoom;Matrix.scaleM(world,0,displayZoom,displayZoom,displayZoom);
        Matrix.rotateM(world,0,frame.pitch,1,0,0);Matrix.rotateM(world,0,frame.yaw,0,1,0);Matrix.rotateM(world,0,frame.roll,0,0,1);Matrix.multiplyMM(camera,0,projection,0,world,0);
        if(!frame.wallpaper)drawGrid();
        if(frame.image!=null){
            if(photoVersion!=frame.imageRevision){GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,frame.image,0);photoVersion=frame.imageRevision;}
            if(meshVersion!=frame.geometryRevision){buildMesh(frame.meshSource);meshVersion=frame.geometryRevision;frame.meshSource=null;}
            if(frame.photo&&meshIndices>0)drawPhoto();
            for(int i=0;i<frame.cutoutCount;i++){
                DrawCutout draw=cutoutPool.get(i);CutoutMesh mesh=cutoutBuffers.get(draw.source.assetKey);
                if(mesh==null){mesh=new CutoutMesh();mesh.build(draw.source);cutoutBuffers.put(draw.source.assetKey,mesh);}mesh.seen=frameNumber;
                Matrix.multiplyMM(draw.mvp,0,camera,0,draw.object,0);drawCutout(mesh,draw.mvp,i==frame.selectedCutout&&!frame.wallpaper,draw.source.feather);
            }
            for(int i=0;i<frame.count;i++){
                DrawStroke draw=pool.get(i);StrokeBuffer gpu=buffers.get(draw.key);if(gpu==null){gpu=new StrokeBuffer();buffers.put(draw.key,gpu);}
                if(draw.changedPoints!=null){long before=gpu.uploadedBytes;gpu.update(draw.changedPoints,draw.radius);geometryUploads++;geometryUploadBytes+=gpu.uploadedBytes-before;}
                gpu.lastSeen=frameNumber;Matrix.multiplyMM(draw.mvp,0,camera,0,draw.object,0);
            }
            if(frame.selected>=0){DrawStroke selected=pool.get(frame.selected);GLES20.glDepthMask(false);drawStroke(buffers.get(selected.key),selected.mvp,0xfff4ffdf,1.65f);GLES20.glDepthMask(true);}
            for(int i=0;i<frame.count;i++){DrawStroke draw=pool.get(i);drawStroke(buffers.get(draw.key),draw.mvp,draw.color,1);}
        }
        for(Iterator<Map.Entry<Object,StrokeBuffer>> it=buffers.entrySet().iterator();it.hasNext();){StrokeBuffer gpu=it.next().getValue();if(gpu.lastSeen!=frameNumber){gpu.delete();it.remove();}}
        for(Iterator<Map.Entry<Object,CutoutMesh>> it=cutoutBuffers.entrySet().iterator();it.hasNext();){CutoutMesh mesh=it.next().getValue();if(mesh.seen!=frameNumber){mesh.delete();it.remove();}}
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,0);GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER,0);
    }
    private void uniforms(float[] m,int color,boolean textured,float radius){
        GLES20.glUniformMatrix4fv(uMatrix,1,false,m,0);GLES20.glUniform4f(uColor,Color.red(color)/255f,Color.green(color)/255f,Color.blue(color)/255f,Color.alpha(color)/255f);GLES20.glUniform1i(uTextured,textured?1:0);GLES20.glUniform1f(uRadius,radius);
        GLES20.glUniform1f(uFeather,0);
    }
    private void noOffset(){GLES20.glDisableVertexAttribArray(aOffset);GLES20.glVertexAttrib3f(aOffset,0,0,0);}
    private void noUV(){GLES20.glDisableVertexAttribArray(aUV);GLES20.glVertexAttrib2f(aUV,0,0);}
    private void drawGrid(){
        uniforms(camera,0xff202b23,false,1);noOffset();noUV();GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,gridVbo);GLES20.glEnableVertexAttribArray(aPosition);GLES20.glVertexAttribPointer(aPosition,3,GLES20.GL_FLOAT,false,12,0);GLES20.glDrawArrays(GLES20.GL_LINES,0,132);
    }
    private void drawPhoto(){
        uniforms(camera,Color.WHITE,true,1);noOffset();GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,meshVbo);GLES20.glEnableVertexAttribArray(aPosition);GLES20.glVertexAttribPointer(aPosition,3,GLES20.GL_FLOAT,false,20,0);GLES20.glEnableVertexAttribArray(aUV);GLES20.glVertexAttribPointer(aUV,2,GLES20.GL_FLOAT,false,20,12);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);GLES20.glUniform1i(uTexture,0);GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER,meshEbo);GLES20.glDrawElements(GLES20.GL_TRIANGLES,meshIndices,GLES20.GL_UNSIGNED_SHORT,0);GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER,0);
    }
    private void drawStroke(StrokeBuffer gpu,float[] m,int color,float scale){
        uniforms(m,color,false,scale);noUV();GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,gpu.id);GLES20.glEnableVertexAttribArray(aPosition);GLES20.glVertexAttribPointer(aPosition,3,GLES20.GL_FLOAT,false,24,0);GLES20.glEnableVertexAttribArray(aOffset);GLES20.glVertexAttribPointer(aOffset,3,GLES20.GL_FLOAT,false,24,12);GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,gpu.vertices);
    }
    private void drawCutout(CutoutMesh mesh,float[] m,boolean selected,float feather){
        uniforms(m,selected?0xfff2ffd4:Color.WHITE,true,1);noOffset();GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,mesh.vbo);
        GLES20.glUniform1f(uFeather,feather);GLES20.glActiveTexture(GLES20.GL_TEXTURE1);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,mesh.distanceTexture);GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glEnableVertexAttribArray(aPosition);GLES20.glVertexAttribPointer(aPosition,3,GLES20.GL_FLOAT,false,20,0);
        GLES20.glEnableVertexAttribArray(aUV);GLES20.glVertexAttribPointer(aUV,2,GLES20.GL_FLOAT,false,20,12);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,mesh.texture);GLES20.glUniform1i(uTexture,0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER,mesh.ebo);GLES20.glDrawElements(GLES20.GL_TRIANGLES,mesh.count,GLES20.GL_UNSIGNED_SHORT,0);GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER,0);
    }
    private void buildMesh(Scene s){
        photoUploads++;
        PhotoMesh mesh=PhotoMesh.build(s);float[] vertices=mesh.vertices;
        ShortBuffer indices=ByteBuffer.allocateDirect(mesh.indices.length*2).order(ByteOrder.nativeOrder()).asShortBuffer();indices.put(mesh.indices).flip();
        meshIndices=mesh.indices.length;photoVertexCount=vertices.length/5;photoTriangleCount=meshIndices/3;
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,meshVbo);GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,vertices.length*4,floats(vertices),GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER,meshEbo);GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER,meshIndices*2,indices,GLES20.GL_STATIC_DRAW);GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER,0);
    }
    void release(){
        for(StrokeBuffer buffer:buffers.values())buffer.delete();buffers.clear();for(CutoutMesh mesh:cutoutBuffers.values())mesh.delete();cutoutBuffers.clear();
        GLES20.glDeleteBuffers(3,new int[]{meshVbo,meshEbo,gridVbo},0);GLES20.glDeleteTextures(1,new int[]{texture},0);GLES20.glDeleteProgram(program);
    }
}
