package studio.depthsketch;

/** A continuous depth surface. Every image cell has two triangles and shares edge vertices. */
final class PhotoMesh {
    final int cols,rows;
    final float[] vertices;
    final short[] indices;
    private PhotoMesh(int cols,int rows){this.cols=cols;this.rows=rows;vertices=new float[(cols+1)*(rows+1)*5];indices=new short[cols*rows*6];}
    static PhotoMesh build(Scene scene){
        // Match new-creator-master/relief/index.html: at most 240 vertices on the long edge,
        // all cells retained, and at most 57,600 vertices in the ES 2 uint16 index path.
        int cols,rows;
        if(scene.aspect>=1){cols=239;rows=Math.max(1,Math.round(240/scene.aspect)-1);}
        else{rows=239;cols=Math.max(1,Math.round(240*scene.aspect)-1);}
        PhotoMesh mesh=new PhotoMesh(cols,rows);int stride=cols+1,n=0;
        for(int y=0;y<=rows;y++)for(int x=0;x<=cols;x++){
            float u=(float)x/cols,v=(float)y/rows,px=(u-.5f)*2*scene.aspect,py=(.5f-v)*2;
            mesh.vertices[n++]=px;mesh.vertices[n++]=py;mesh.vertices[n++]=scene.surface(px,py);mesh.vertices[n++]=u;mesh.vertices[n++]=v;
        }
        n=0;
        for(int y=0;y<rows;y++)for(int x=0;x<cols;x++){
            int a=y*stride+x,b=a+1,c=a+stride,d=c+1;
            // Same diagonal and shared vertices as the original project's complete grid.
            mesh.indices[n++]=(short)a;mesh.indices[n++]=(short)c;mesh.indices[n++]=(short)b;
            mesh.indices[n++]=(short)b;mesh.indices[n++]=(short)c;mesh.indices[n++]=(short)d;
        }
        return mesh;
    }
}
