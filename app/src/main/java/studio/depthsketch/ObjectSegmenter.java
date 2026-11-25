package studio.depthsketch;

import android.content.Context;
import android.graphics.Bitmap;
import ai.onnxruntime.*;
import java.nio.FloatBuffer;
import java.util.*;

/** MobileSAM: a cached image embedding and positive/negative pixel prompts. Worker thread only. */
final class ObjectSegmenter implements AutoCloseable {
    private final Context context;
    private final OrtEnvironment environment=OrtEnvironment.getEnvironment();
    private OrtSession decoder;
    private OnnxTensor embedding;
    private int originalW,originalH,resizedW,resizedH;
    MaskCleanup.Result lastCleanup;
    ObjectSegmenter(Context context){this.context=context.getApplicationContext();}
    private OrtSession open(String name)throws Exception {
        try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){
            options.setIntraOpNumThreads(Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors())));
            options.setInterOpNumThreads(1);options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            return environment.createSession(ModelFiles.get(context,name).getPath(),options);
        }
    }
    void prepare(Bitmap image)throws Exception {
        originalW=image.getWidth();originalH=image.getHeight();float ratio=1024f/Math.max(originalW,originalH);
        resizedW=Math.round(originalW*ratio);resizedH=Math.round(originalH*ratio);
        Bitmap small=Bitmap.createScaledBitmap(image,resizedW,resizedH,true);
        int[] pixels=new int[resizedW*resizedH];small.getPixels(pixels,0,resizedW,0,0,resizedW,resizedH);
        float[] rgb=new float[pixels.length*3];
        for(int i=0,j=0;i<pixels.length;i++){rgb[j++]=(pixels[i]>>16)&255;rgb[j++]=(pixels[i]>>8)&255;rgb[j++]=pixels[i]&255;}
        if(small!=image)small.recycle();
        try(OrtSession encoder=open("mobile_sam_encoder.onnx");
            OnnxTensor input=OnnxTensor.createTensor(environment,FloatBuffer.wrap(rgb),new long[]{resizedH,resizedW,3});
            OrtSession.Result result=encoder.run(Collections.singletonMap(encoder.getInputNames().iterator().next(),input))){
            OnnxTensor output=(OnnxTensor)result.get(0);FloatBuffer values=output.getFloatBuffer();
            float[] data=new float[values.remaining()];values.get(data);
            if(embedding!=null)embedding.close();embedding=OnnxTensor.createTensor(environment,FloatBuffer.wrap(data),output.getInfo().getShape());
        }
        // The encoder session is released; refinement only needs the 4 MB embedding and decoder.
        if(decoder==null)decoder=open("mobile_sam_decoder.onnx");
    }
    float[] segment(List<float[]> prompts)throws Exception {
        if(embedding==null||prompts.isEmpty())throw new IllegalStateException("请先点击主体");
        int n=prompts.size()+1;float[] coordinates=new float[n*2],labels=new float[n];
        for(int i=0;i<prompts.size();i++){
            float[] p=prompts.get(i);coordinates[i*2]=p[0]*resizedW/originalW;coordinates[i*2+1]=p[1]*resizedH/originalH;labels[i]=p[2];
        }labels[n-1]=-1;
        Map<String,OnnxTensor> inputs=new HashMap<>();inputs.put("image_embeddings",embedding);
        inputs.put("point_coords",tensor(coordinates,1,n,2));inputs.put("point_labels",tensor(labels,1,n));
        inputs.put("mask_input",tensor(new float[256*256],1,1,256,256));
        inputs.put("has_mask_input",tensor(new float[]{0},1));inputs.put("orig_im_size",tensor(new float[]{originalH,originalW},2));
        try(OrtSession.Result result=decoder.run(inputs)){
            OnnxTensor masks=(OnnxTensor)result.get("masks").orElse(result.get(0));FloatBuffer buffer=masks.getFloatBuffer();
            if(buffer.remaining()!=originalW*originalH)throw new IllegalStateException("分割输出尺寸不匹配");
            float[] mask=new float[buffer.remaining()];buffer.get(mask);
            lastCleanup=MaskCleanup.clean(mask,originalW,originalH,prompts);return mask;
        }finally{for(Map.Entry<String,OnnxTensor> item:inputs.entrySet())if(!item.getKey().equals("image_embeddings"))item.getValue().close();}
    }
    private OnnxTensor tensor(float[] values,long...shape)throws OrtException{return OnnxTensor.createTensor(environment,FloatBuffer.wrap(values),shape);}
    static Scene.Cutout crop(Bitmap image,float[] logits,DepthEstimator.Result fullDepth)throws Exception {
        int w=image.getWidth(),h=image.getHeight();if(logits.length!=w*h)throw new IllegalArgumentException("分割尺寸无效");
        int left=w,top=h,right=-1,bottom=-1,count=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(logits[y*w+x]>0){left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);count++;}
        if(count<30||right-left<2||bottom-top<2)throw new IllegalStateException("没有选中有效主体，请点击对象内部或补充前景点");
        left=Math.max(0,left-3);top=Math.max(0,top-3);right=Math.min(w-1,right+3);bottom=Math.min(h-1,bottom+3);
        int cw=right-left+1,ch=bottom-top+1;int[] pixels=new int[cw*ch];image.getPixels(pixels,0,cw,left,top,cw,ch);
        float[] depth=new float[cw*ch],inside=new float[count];int k=0;
        for(int y=0;y<ch;y++)for(int x=0;x<cw;x++){
            int original=(top+y)*w+left+x,i=y*cw+x;
            float logit=logits[original];if(!Float.isFinite(logit))throw new IllegalStateException("无效的分割结果");
            int alpha=Math.round(255*Math.max(0,Math.min(1,.5f+logit)));
            pixels[i]=(pixels[i]&0xffffff)|(alpha<<24);
            depth[i]=Scene.sample(fullDepth.values,fullDepth.width,fullDepth.height,(left+x)/(float)Math.max(1,w-1),(top+y)/(float)Math.max(1,h-1));
            if(logit>0)inside[k++]=depth[i];
        }
        Arrays.sort(inside,0,k);float lo=inside[(int)(k*.02)],hi=inside[Math.min(k-1,(int)(k*.98))];
        for(int i=0;i<depth.length;i++)depth[i]=Math.max(lo,Math.min(hi,depth[i]));
        Scene.Cutout cutout=new Scene.Cutout();cutout.image=Bitmap.createBitmap(pixels,cw,ch,Bitmap.Config.ARGB_8888);
        cutout.depth=depth;cutout.width=cw;cutout.height=ch;cutout.centerDepth=inside[k/2];return cutout;
    }
    @Override public void close(){if(embedding!=null)embedding.close();embedding=null;if(decoder!=null)try{decoder.close();}catch(OrtException ignored){}decoder=null;}
}
