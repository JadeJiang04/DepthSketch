package studio.depthsketch;

import android.content.Context;
import android.graphics.Bitmap;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Collections;
import ai.onnxruntime.*;

/** DA2 Small FP32, RGB + ImageNet normalization. The result is relative inverse depth. */
public final class DepthEstimator implements AutoCloseable {
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private OrtSession session;
    private final Context context;
    public DepthEstimator(Context context) { this.context=context.getApplicationContext(); }
    public static final class Result {
        public float[] values;
        public int width,height;
        public long millis;
    }
    private void load() throws Exception {
        if (session!=null) return;
        File model=new File(context.getFilesDir(),"depth_anything_v2_small.onnx");
        long expected;
        try (android.content.res.AssetFileDescriptor fd=context.getAssets().openFd("depth_anything_v2_small.onnx")) {
            expected=fd.getLength();
        }
        if (!model.exists() || model.length()!=expected) {
            File part=new File(model.getPath()+".part");
            try (InputStream in=context.getAssets().open("depth_anything_v2_small.onnx"); FileOutputStream out=new FileOutputStream(part)) {
                byte[] buffer=new byte[1024*1024]; int n;
                while ((n=in.read(buffer))!=-1) out.write(buffer,0,n);
            }
            if (!part.renameTo(model)) throw new java.io.IOException("无法准备深度模型");
        }
        try (OrtSession.SessionOptions options=new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors())));
            options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session=environment.createSession(model.getAbsolutePath(),options);
        }
    }
    public Result estimate(Bitmap image) throws Exception {
        long start=System.nanoTime();
        load();
        String input=session.getInputNames().iterator().next();
        TensorInfo info=(TensorInfo)session.getInputInfo().get(input).getInfo();
        long[] shape=info.getShape();
        // Dynamic exports permit a 280 px short side for a lighter mobile working resolution.
        float scale=Math.min(280f/Math.min(image.getWidth(),image.getHeight()),504f/Math.max(image.getWidth(),image.getHeight()));
        int h=Math.max(56,Math.round(image.getHeight()*scale/14)*14);
        int w=Math.max(56,Math.round(image.getWidth()*scale/14)*14);
        if (shape[2]>0) h=(int)shape[2];
        if (shape[3]>0) w=(int)shape[3];
        Bitmap scaled=Bitmap.createScaledBitmap(image,w,h,true);
        int[] pixels=new int[w*h]; scaled.getPixels(pixels,0,w,0,0,w,h);
        float[] rgb=new float[3*w*h];
        float[] means={.485f,.456f,.406f}, std={.229f,.224f,.225f};
        for (int i=0;i<pixels.length;i++) {
            rgb[i]=(((pixels[i]>>16)&255)/255f-means[0])/std[0];
            rgb[w*h+i]=(((pixels[i]>>8)&255)/255f-means[1])/std[1];
            rgb[2*w*h+i]=((pixels[i]&255)/255f-means[2])/std[2];
        }
        if (scaled!=image) scaled.recycle();
        try (OnnxTensor tensor=OnnxTensor.createTensor(environment,FloatBuffer.wrap(rgb),new long[]{1,3,h,w});
             OrtSession.Result prediction=session.run(Collections.singletonMap(input,tensor))) {
            OnnxTensor output=(OnnxTensor)prediction.get(0);
            long[] dimensions=output.getInfo().getShape();
            Result result=new Result();
            result.height=(int)dimensions[dimensions.length-2];
            result.width=(int)dimensions[dimensions.length-1];
            FloatBuffer buffer=output.getFloatBuffer();
            result.values=new float[result.width*result.height]; buffer.get(result.values);
            float[] ordered=result.values.clone();
            for (float value: ordered) if (!Float.isFinite(value)) throw new IllegalStateException("模型返回无效深度");
            Arrays.sort(ordered);
            float min=ordered[(int)(ordered.length*.02)], max=ordered[Math.min(ordered.length-1,(int)(ordered.length*.98))];
            float range=Math.max(1e-6f,max-min);
            for (int i=0;i<result.values.length;i++) result.values[i]=Math.max(0,Math.min(1,(result.values[i]-min)/range));
            result.millis=(System.nanoTime()-start)/1_000_000;
            return result;
        }
    }
    @Override public void close() {
        if (session!=null) { try { session.close(); } catch (OrtException ignored) {} session=null; }
    }
}
