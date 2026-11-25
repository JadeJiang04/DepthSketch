package studio.depthsketch;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import java.nio.ByteBuffer;
import org.json.*;

/** Discontinuous depth must not punch holes, and foreground depth must still hide inserted objects. */
final class PhotoMeshChecks {
    static JSONObject run()throws Exception {
        JSONObject result=new JSONObject();
        Scene scene=new Scene();Bitmap photo=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);photo.eraseColor(0xffdce4eb);
        float[] depth=new float[256*256];for(int y=0;y<256;y++)for(int x=0;x<256;x++)depth[y*256+x]=x<128?1:0;
        scene.setImage(photo,depth,256,256);scene.relief=1.5f;scene.wallpaper=true;scene.zoom=1/1.1f;
        PhotoMesh mesh=PhotoMesh.build(scene);check(mesh.vertices.length/5<=65535,"uint16 overflow");
        double area=0;
        for(int i=0;i<mesh.indices.length;i+=3){int a=(mesh.indices[i]&65535)*5,b=(mesh.indices[i+1]&65535)*5,c=(mesh.indices[i+2]&65535)*5;
            float cross=(mesh.vertices[b]-mesh.vertices[a])*(mesh.vertices[c+1]-mesh.vertices[a+1])-(mesh.vertices[b+1]-mesh.vertices[a+1])*(mesh.vertices[c]-mesh.vertices[a]);check(cross>0,"triangle winding or zero area");area+=cross/2;
        }
        check(Math.abs(area-4)<.001,"mesh does not cover the full photo");result.put("continuous_surface_and_uint16_indices","PASS");
        try(ScenePreviewOutputs.Pbuffer egl=new ScenePreviewOutputs.Pbuffer(256,256)){
            GpuScene gpu=new GpuScene(scene);gpu.init();JSONArray coverage=new JSONArray();
            for(float[] angles:new float[][]{{0,0},{6,-6},{-6,6}}){
                scene.pitch=angles[0];scene.yaw=angles[1];gpu.draw(256,256);GLES20.glFinish();ByteBuffer rgba=ByteBuffer.allocateDirect(256*256*4);GLES20.glReadPixels(0,0,256,256,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,rgba);
                int missing=0;for(int y=24;y<232;y++)for(int x=24;x<232;x++){int i=(y*256+x)*4;if(Math.abs((rgba.get(i)&255)-220)>2||Math.abs((rgba.get(i+1)&255)-228)>2||Math.abs((rgba.get(i+2)&255)-235)>2)missing++;}
                check(missing==0,"depth-edge holes at "+angles[0]+","+angles[1]+": "+missing);coverage.put(new JSONObject().put("pitch",angles[0]).put("yaw",angles[1]).put("missing_interior_pixels",missing));
            }
            result.put("sharp_depth_step_gpu_coverage",coverage);
            scene.pitch=scene.yaw=0;Scene.Cutout cutout=new Scene.Cutout();cutout.width=cutout.height=64;cutout.image=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);cutout.image.eraseColor(0xff00ff00);cutout.depth=new float[64*64];java.util.Arrays.fill(cutout.depth,.5f);cutout.z=0;cutout.scale=.5f;scene.cutouts.add(cutout);gpu.draw(256,256);GLES20.glFinish();
            ByteBuffer samples=ByteBuffer.allocateDirect(4);GLES20.glReadPixels(85,128,1,1,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,samples);check((samples.get(0)&255)>200&&(samples.get(2)&255)>200,"near photo no longer occludes object");
            GLES20.glReadPixels(171,128,1,1,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,samples);check((samples.get(0)&255)<5&&(samples.get(1)&255)>250&&(samples.get(2)&255)<5,"object behind far photo");result.put("near_occludes_and_far_reveals_cutout","PASS");
            cutout.feather=0;gpu.draw(256,256);GLES20.glReadPixels(190,128,1,1,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,samples);int raw=samples.get(0)&255;
            cutout.feather=4;gpu.draw(256,256);GLES20.glReadPixels(190,128,1,1,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,samples);check((samples.get(0)&255)>raw+30,"edge feather did not blend with background");
            GLES20.glReadPixels(171,128,1,1,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,samples);check((samples.get(0)&255)<5&&(samples.get(1)&255)>250,"feather changed object interior");result.put("feather_edge_only_reversible_uniform","PASS");
            scene.zoom=3;scene.panX=.8f;scene.panY=.6f;gpu.draw(256,256);GLES20.glReadPixels(220,128,1,1,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,samples);check((samples.get(0)&255)>200&&(samples.get(2)&255)>200,"editor zoom leaked into wallpaper framing");result.put("wallpaper_ignores_editor_pan_zoom","PASS");
            check(GLES20.glGetError()==GLES20.GL_NO_ERROR,"GL error");
        }
        return result;
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
