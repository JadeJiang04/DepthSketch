package studio.depthsketch;

import android.opengl.GLES20;
import java.nio.*;

/** GL-thread owned persistent geometry. Appending a point updates only the last two segments. */
final class StrokeBuffer {
    private static final int SIDES=8,FLOATS_PER_VERTEX=6,SEGMENT_FLOATS=SIDES*6*FLOATS_PER_VERTEX;
    private static final float[] COS=new float[SIDES],SIN=new float[SIDES];
    static {for(int i=0;i<SIDES;i++){COS[i]=(float)Math.cos(i*2*Math.PI/SIDES);SIN[i]=(float)Math.sin(i*2*Math.PI/SIDES);}}
    int id,pointCount,vertices,capacityBytes;
    float radius;
    private FloatBuffer staging;
    private final float[][] ringA=new float[SIDES][3],ringB=new float[SIDES][3];
    long updates,uploadedBytes;
    long lastSeen;

    void update(float[] p,float brushRadius) {
        int count=p.length/3;
        if(id==0){int[] name=new int[1];GLES20.glGenBuffers(1,name,0);id=name[0];}
        int required=count<=1?SIDES*3*FLOATS_PER_VERTEX:(count-1)*SEGMENT_FLOATS+SEGMENT_FLOATS;
        int start=Math.max(0,pointCount-2);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,id);
        if(required*4>capacityBytes || count<pointCount || radius!=brushRadius) {
            int bytes=Math.max(4096,capacityBytes);
            while(bytes<required*4)bytes*=2;
            capacityBytes=bytes;GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,bytes,null,GLES20.GL_DYNAMIC_DRAW);start=0;
        }
        int offset=count<=1?0:start*SEGMENT_FLOATS;
        int floats=required-offset;
        if(staging==null||staging.capacity()<floats) {
            int size=Math.max(1024,staging==null?0:staging.capacity());while(size<floats)size*=2;
            staging=ByteBuffer.allocateDirect(size*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        staging.clear();radius=brushRadius;
        if(count==1){
            for(int k=0;k<SIDES;k++){
                vertex(p,0,0,0,0);vertex(p,0,COS[k]*radius,SIN[k]*radius,0);
                int next=(k+1)%SIDES;vertex(p,0,COS[next]*radius,SIN[next]*radius,0);
            }
        }else if(count>1){
            ring(p,start,ringA);
            for(int i=start;i<count-1;i++){
                ring(p,i+1,ringB);
                for(int k=0;k<SIDES;k++){
                    int next=(k+1)%SIDES;
                    vertex(p,i,ringA[k]);vertex(p,i+1,ringB[k]);vertex(p,i,ringA[next]);
                    vertex(p,i,ringA[next]);vertex(p,i+1,ringB[k]);vertex(p,i+1,ringB[next]);
                }
                for(int k=0;k<SIDES;k++)System.arraycopy(ringB[k],0,ringA[k],0,3);
            }
            // Both caps live after the segment range; the old cap range becomes the new segment.
            ring(p,0,ringA);ring(p,count-1,ringB);
            for(int k=0;k<SIDES;k++){
                int next=(k+1)%SIDES;
                vertex(p,0,0,0,0);vertex(p,0,ringA[k]);vertex(p,0,ringA[next]);
                vertex(p,count-1,0,0,0);vertex(p,count-1,ringB[k]);vertex(p,count-1,ringB[next]);
            }
        }
        staging.flip();GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER,offset*4,staging.remaining()*4,staging);
        uploadedBytes+=staging.limit()*4L;updates++;pointCount=count;vertices=required/FLOATS_PER_VERTEX;
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,0);
    }
    private void vertex(float[] p,int index,float[] offset){vertex(p,index,offset[0],offset[1],offset[2]);}
    private void vertex(float[] p,int index,float ox,float oy,float oz){
        int i=index*3;staging.put(p[i]).put(p[i+1]).put(p[i+2]).put(ox).put(oy).put(oz);
    }
    private void ring(float[] p,int index,float[][] out){
        int a=Math.max(0,index-1)*3,b=Math.min(p.length/3-1,index+1)*3;
        float tx=p[b]-p[a],ty=p[b+1]-p[a+1],tz=p[b+2]-p[a+2];
        float length=(float)Math.sqrt(tx*tx+ty*ty+tz*tz);
        if(length<1e-7f){tx=1;ty=tz=0;length=1;}tx/=length;ty/=length;tz/=length;
        float nx=-ty,ny=tx,nz=0,nl=(float)Math.sqrt(nx*nx+ny*ny);
        if(nl<.01f){nx=1;ny=nz=0;nl=1;}nx/=nl;ny/=nl;
        float bx=ty*nz-tz*ny,by=tz*nx-tx*nz,bz=tx*ny-ty*nx;
        for(int k=0;k<SIDES;k++){
            out[k][0]=radius*(COS[k]*nx+SIN[k]*bx);
            out[k][1]=radius*(COS[k]*ny+SIN[k]*by);
            out[k][2]=radius*(COS[k]*nz+SIN[k]*bz);
        }
    }
    void delete(){if(id!=0)GLES20.glDeleteBuffers(1,new int[]{id},0);id=0;}
}
