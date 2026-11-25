package studio.depthsketch;

import android.opengl.*;
import java.nio.*;

/** Immutable RGBA + relative depth source; placement and relief are matrix-only changes. */
final class CutoutMesh {
    int texture,distanceTexture,vbo,ebo,count;
    long seen;
    void build(Scene.Cutout c){
        int cols=Math.min(140,Math.max(20,c.image.getWidth()/3)),rows=Math.min(140,Math.max(20,c.image.getHeight()/3));
        float[] points=new float[(cols+1)*(rows+1)*5];int n=0;
        for(int y=0;y<=rows;y++)for(int x=0;x<=cols;x++){
            float u=(float)x/cols,v=(float)y/rows;
            points[n++]=(u-.5f)*2*c.aspect();points[n++]=(.5f-v)*2;points[n++]=c.depthAt(u,v)-c.centerDepth;points[n++]=u;points[n++]=v;
        }
        ShortBuffer indices=ByteBuffer.allocateDirect(cols*rows*12).order(ByteOrder.nativeOrder()).asShortBuffer();
        for(int y=0;y<rows;y++)for(int x=0;x<cols;x++){int a=y*(cols+1)+x,b=a+1,d=a+cols+1,e=d+1;indices.put((short)a).put((short)d).put((short)b).put((short)b).put((short)d).put((short)e);}
        count=indices.position();indices.flip();FloatBuffer vertices=ByteBuffer.allocateDirect(points.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();vertices.put(points).flip();
        int[] ids=new int[2];GLES20.glGenBuffers(2,ids,0);vbo=ids[0];ebo=ids[1];
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,vbo);GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,points.length*4,vertices,GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER,ebo);GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER,count*2,indices,GLES20.GL_STATIC_DRAW);GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER,0);
        GLES20.glGenTextures(1,ids,0);texture=ids[0];GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,c.image,0);
        byte[] distances=EdgeFeather.distances(c.image);ByteBuffer mask=ByteBuffer.allocateDirect(distances.length);mask.put(distances).flip();
        GLES20.glGenTextures(1,ids,0);distanceTexture=ids[0];GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,distanceTexture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT,1);GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_LUMINANCE,c.width,c.height,0,GLES20.GL_LUMINANCE,GLES20.GL_UNSIGNED_BYTE,mask);GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT,4);
    }
    static void matrix(float[] m,Scene.Cutout c){
        Matrix.setIdentityM(m,0);Matrix.translateM(m,0,c.x,c.y,c.z);Matrix.rotateM(m,0,c.rz,0,0,1);Matrix.rotateM(m,0,c.ry,0,1,0);Matrix.rotateM(m,0,c.rx,1,0,0);Matrix.scaleM(m,0,c.scale,c.scale,c.scale*Math.max(.001f,c.relief));
    }
    void delete(){GLES20.glDeleteBuffers(2,new int[]{vbo,ebo},0);GLES20.glDeleteTextures(2,new int[]{texture,distanceTexture},0);}
}
