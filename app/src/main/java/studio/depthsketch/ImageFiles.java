package studio.depthsketch;
import android.graphics.*;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import java.io.*;
final class ImageFiles {
    static Bitmap decode(android.content.Context context,Uri uri) throws Exception {
        if(uri==null){try(InputStream in=context.getAssets().open("sample.jpg")){return BitmapFactory.decodeStream(in);}}
        if(Build.VERSION.SDK_INT>=28){
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.getContentResolver(),uri),(decoder,info,source)->{
                int w=info.getSize().getWidth(),h=info.getSize().getHeight();float scale=Math.min(1,1536f/Math.max(w,h));
                decoder.setTargetSize(Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)));decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        }
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        try(InputStream in=context.getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,options);}
        int size=1;while(Math.max(options.outWidth,options.outHeight)/size>1536)size*=2;
        options.inJustDecodeBounds=false;options.inSampleSize=size;Bitmap bitmap;
        try(InputStream in=context.getContentResolver().openInputStream(uri)){bitmap=BitmapFactory.decodeStream(in,null,options);}
        if(bitmap==null)throw new IOException("无法读取该照片");
        try(InputStream in=context.getContentResolver().openInputStream(uri)){
            int orientation=new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);Matrix m=new Matrix();
            switch(orientation){case 2:m.setScale(-1,1);break;case 3:m.setRotate(180);break;case 4:m.setScale(1,-1);break;case 5:m.setRotate(90);m.postScale(-1,1);break;case 6:m.setRotate(90);break;case 7:m.setRotate(270);m.postScale(-1,1);break;case 8:m.setRotate(270);break;}
            if(!m.isIdentity())bitmap=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),m,true);
        }return bitmap;
    }
}
