package studio.depthsketch;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.*;

final class MaskPickerView extends View {
    Bitmap image,overlay;
    final List<float[]> prompts=new ArrayList<>();
    interface PointListener{void point(float x,float y);}
    PointListener listener;
    final Paint paint=new Paint(3);
    final RectF bounds=new RectF();
    private float downX,downY;
    MaskPickerView(Context c){super(c);setContentDescription("点击新图片中的抠图对象");}
    private void fit(){
        if(image==null)return;float scale=Math.min((getWidth()-32f)/image.getWidth(),(getHeight()-32f)/image.getHeight());
        float w=image.getWidth()*scale,h=image.getHeight()*scale;bounds.set((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);
    }
    float[] screenPoint(float u,float v){fit();return new float[]{bounds.left+bounds.width()*u,bounds.top+bounds.height()*v};}
    @Override protected void onDraw(Canvas canvas){
        canvas.drawColor(MainActivity.BG);if(image==null)return;fit();paint.setAlpha(255);canvas.drawBitmap(image,null,bounds,paint);
        if(overlay!=null)canvas.drawBitmap(overlay,null,bounds,paint);
        for(float[] point:prompts){float x=bounds.left+point[0]/image.getWidth()*bounds.width(),y=bounds.top+point[1]/image.getHeight()*bounds.height();
            paint.setColor(Color.WHITE);canvas.drawCircle(x,y,9,paint);paint.setColor(point[2]>0?MainActivity.ACCENT:0xffff8181);canvas.drawCircle(x,y,6,paint);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){downX=e.getX();downY=e.getY();return true;}
        if(e.getActionMasked()==MotionEvent.ACTION_UP){
            if(isEnabled()&&image!=null&&bounds.contains(e.getX(),e.getY())&&Math.hypot(e.getX()-downX,e.getY()-downY)<30&&listener!=null)
                listener.point((e.getX()-bounds.left)/bounds.width()*image.getWidth(),(e.getY()-bounds.top)/bounds.height()*image.getHeight());
            performClick();return true;
        }return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
