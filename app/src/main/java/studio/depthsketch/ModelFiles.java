package studio.depthsketch;

import android.content.Context;
import java.io.*;

final class ModelFiles {
    static File get(Context context,String asset)throws IOException {
        File target=new File(context.getFilesDir(),asset);long size;
        try(android.content.res.AssetFileDescriptor fd=context.getAssets().openFd(asset)){size=fd.getLength();}
        if(target.length()==size)return target;
        File temporary=new File(target.getPath()+".part");
        try(InputStream in=context.getAssets().open(asset);OutputStream out=new FileOutputStream(temporary)){
            byte[] block=new byte[1024*1024];int n;while((n=in.read(block))!=-1)out.write(block,0,n);
        }
        if(!temporary.renameTo(target))throw new IOException("无法准备本地模型");return target;
    }
}
