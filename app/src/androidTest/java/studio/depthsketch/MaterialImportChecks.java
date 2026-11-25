package studio.depthsketch;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;

/** Prepared RGBA and opaque artwork go through the real picker result and depth inference. */
final class MaterialImportChecks {
    private final Instrumentation test;private final MainActivity main;private final Bundle result=new Bundle();
    MaterialImportChecks(Instrumentation test,MainActivity main){this.test=test;this.main=main;}
    Bundle run()throws Exception {
        Scene base=new Scene();Bitmap background=ImageFiles.decode(main,null);base.setImage(background,new float[]{.5f},1,1);ui(()->main.applyProject(base));
        Bitmap transparent=Bitmap.createBitmap(64,96,Bitmap.Config.ARGB_8888);int[] rgba=new int[64*96];
        for(int y=20;y<80;y++)for(int x=12;x<52;x++)rgba[y*64+x]=(x==12?0x40408ac0:0xff408ac0);rgba[2*64+2]=0xffeeaa33;transparent.setPixels(rgba,0,64,0,0,64,96);
        Bitmap input=ImageMaterial.depthInput(transparent);check(input!=transparent&&(input.getPixel(0,0)>>>24)==255,"opaque depth input");check(transparent.getPixel(0,0)==0,"depth compositing changed original");input.recycle();
        Bitmap empty=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);boolean rejected=false;try{ImageMaterial.depthInput(empty);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"fully transparent image accepted");pass("rgba_depth_input_and_empty_rejection");
        File png=new File(main.getCacheDir(),"material-import-test.png");try(OutputStream out=new FileOutputStream(png)){transparent.compress(Bitmap.CompressFormat.PNG,100,out);}
        Instrumentation.ActivityMonitor segmentation=test.addMonitor(CutoutActivity.class.getName(),null,false);
        IntentFilter pickerFilter=new IntentFilter(Intent.ACTION_OPEN_DOCUMENT);pickerFilter.addCategory(Intent.CATEGORY_OPENABLE);pickerFilter.addDataType("image/*");Instrumentation.ActivityMonitor picker=test.addMonitor(pickerFilter,new Instrumentation.ActivityResult(Activity.RESULT_OK,new Intent().setData(Uri.fromFile(png))),true);
        ui(()->findText(main.getWindow().getDecorView(),"⋯").performClick());SystemClock.sleep(200);
        // Use UiAutomation to click the real menu item in its separate dialog window.
        android.view.accessibility.AccessibilityNodeInfo root=null,entry=null;long menuDeadline=SystemClock.elapsedRealtime()+5000;
        while(entry==null&&SystemClock.elapsedRealtime()<menuDeadline){root=test.getUiAutomation().getRootInActiveWindow();if(root!=null)for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText("直接导入图片素材"))if("直接导入图片素材".contentEquals(node.getText())){entry=node;break;}if(entry==null)SystemClock.sleep(100);}
        check(entry!=null,"direct import menu missing");Rect bounds=new Rect();entry.getBoundsInScreen(bounds);check(!bounds.isEmpty(),"import menu is off screen");long tap=SystemClock.uptimeMillis();test.sendPointerSync(MotionEvent.obtain(tap,tap,MotionEvent.ACTION_DOWN,bounds.exactCenterX(),bounds.exactCenterY(),0));test.sendPointerSync(MotionEvent.obtain(tap,tap+50,MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY(),0));
        test.waitForIdleSync();check(picker.getHits()==1,"system image picker was not opened");long deadline=SystemClock.elapsedRealtime()+150000;while((main.scene.cutouts.isEmpty()||main.busy)&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100);test.removeMonitor(picker);check(main.scene.cutouts.size()==1&&!main.busy,"PNG import timed out");check(main.scene.image==background,"material replaced background");check(segmentation.getHits()==0,"direct material opened segmentation");test.removeMonitor(segmentation);
        Scene.Cutout object=main.scene.cutouts.get(0);check(object.width==64&&object.height==96&&object.depth.length==64*96,"aligned depth dimensions");check(object.feather==0,"prepared alpha feathered by default");
        int[] actual=new int[rgba.length];object.image.getPixels(actual,0,64,0,0,64,96);for(int i=0;i<actual.length;i++){check((actual[i]>>>24)==(rgba[i]>>>24),"alpha changed at "+i);check(Float.isFinite(object.depth[i]),"invalid depth");if((actual[i]>>>24)==0)check(object.depth[i]==object.centerDepth,"transparent depth not neutralized");}
        check((actual[2*64+2]>>>24)==255,"disconnected artwork was deleted");pass("system_picker_direct_rgba_no_segmentation_real_depth");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();ProjectStore.write(bytes,main.scene);Scene restored=ProjectStore.read(new ByteArrayInputStream(bytes.toByteArray()));check((restored.cutouts.get(0).image.getPixel(12,30)>>>24)==64,"project lost semitransparency");
        ui(()->findText(main.getWindow().getDecorView(),"撤回").performClick());check(main.scene.cutouts.isEmpty(),"material undo");ui(()->findText(main.getWindow().getDecorView(),"重做").performClick());check(main.scene.cutouts.size()==1,"material redo");pass("prepared_material_project_undo_redo");
        Bitmap opaque=Bitmap.createBitmap(48,72,Bitmap.Config.ARGB_8888);opaque.eraseColor(0xff638ca2);check(ImageMaterial.depthInput(opaque)==opaque,"opaque artwork needlessly copied");File jpg=new File(main.getCacheDir(),"material-import-test.jpg");try(OutputStream out=new FileOutputStream(jpg)){opaque.compress(Bitmap.CompressFormat.JPEG,95,out);}
        ui(()->main.importMaterial(Uri.fromFile(jpg)));deadline=SystemClock.elapsedRealtime()+150000;while((main.busy||main.scene.cutouts.size()<2)&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100);check(!main.busy&&main.scene.cutouts.size()==2,"opaque material import");Scene.Cutout rectangle=main.scene.cutouts.get(1);check(rectangle.width==48&&rectangle.height==72&&(rectangle.image.getPixel(0,0)>>>24)==255,"opaque image did not remain a rectangle");pass("opaque_picture_whole_rectangle_import");
        png.delete();jpg.delete();result.putString("result","PASS");return result;
    }
    private void ui(Runnable r){test.runOnMainSync(r);test.waitForIdleSync();}
    private void pass(String name){result.putString(name,"PASS");Bundle b=new Bundle();b.putString("stream","PASS "+name+"\n");test.sendStatus(0,b);}
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static View findText(View v,String text){if(v instanceof TextView&&text.equals(((TextView)v).getText().toString()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=findText(((ViewGroup)v).getChildAt(i),text);if(found!=null)return found;}return null;}
}
