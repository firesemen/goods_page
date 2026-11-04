package ru.fridgedays.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import ru.fridgedays.core.*;

/** Real offline native OCR + draft confirmation + ordinary encrypted inventory save. */
public final class PhotoInstrumentation extends Instrumentation {
 private int checks;
 private Bundle status(){Bundle b=new Bundle();b.putString("id","InstrumentationTestRunner");b.putInt("numtests",1);b.putInt("current",1);b.putString("class",getClass().getName());b.putString("test","offlinePhotoDraft");return b;}
 @Override public void onCreate(Bundle b){super.onCreate(b);start();}
 private void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
 private boolean ui(BooleanSupplier call){boolean[] b={false};runOnMainSync(()->b[0]=call.getAsBoolean());return b[0];}
 private void until(BooleanSupplier ready,String message)throws Exception{long end=System.nanoTime()+180_000_000_000L;while(!ready.getAsBoolean()){if(System.nanoTime()>end)throw new AssertionError(message);Thread.sleep(100);}}
 private AlertDialog review(EditorActivity editor){try{var field=EditorActivity.class.getDeclaredField("reviewDialog");field.setAccessible(true);return (AlertDialog)field.get(editor);}catch(Exception e){throw new RuntimeException(e);}}
 private Button button(Activity a,String label){List<View> all=new ArrayList<>();collect(a.getWindow().getDecorView(),all);return all.stream().filter(v->v instanceof Button b&&b.getText().toString().equals(label)).map(v->(Button)v).findFirst().orElseThrow();}
 private void collect(View v,List<View> all){all.add(v);if(v instanceof ViewGroup group)for(int n=0;n<group.getChildCount();n++)collect(group.getChildAt(n),all);}
 private CheckBox choice(AlertDialog d,String key){return d.getWindow().getDecorView().findViewWithTag("review-"+key);}
 private Uri fixture(Context c,String...lines)throws Exception{
  ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,"fridge-ocr-test.jpg");values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");Uri uri=c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
  Bitmap image=Bitmap.createBitmap(1800,1000,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(Color.WHITE);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.BLACK);paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));paint.setTextSize(64);int y=160;for(String line:lines){canvas.drawText(line,100,y,paint);y+=150;}
  try(OutputStream out=c.getContentResolver().openOutputStream(uri)){image.compress(Bitmap.CompressFormat.JPEG,95,out);}image.recycle();return uri;
 }
 @Override public void onStart(){sendStatus(1,status());Bundle result=new Bundle();EditorActivity editor=null;Store store=null;String created=null;List<Uri> fixtures=new ArrayList<>();
  try{
   Context c=getTargetContext();store=Store.get(c);Store inventory=store;int packets=store.packets().size();String priorEndpoint=PhotoCloudClient.endpoint(c);c.getSharedPreferences("photo-recognition",0).edit().remove("url").commit();
   editor=(EditorActivity)startActivitySync(new Intent(c,EditorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));EditorActivity form=editor;waitForIdleSync();
   Uri front=fixture(c,"МОЛОКО ПАСТЕРИЗОВАННОЕ","Объем 500 мл");fixtures.add(front);
   runOnMainSync(()->form.onActivityResult(21,Activity.RESULT_OK,new Intent().setData(front)));until(()->ui(()->button(form,"Сохранить упаковку").isEnabled()),"front image was not imported");
   var productField=EditorActivity.class.getDeclaredField("product");productField.setAccessible(true);String id=(String)productField.get(form);
   BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(PhotoImages.source(c,id,false).getAbsolutePath(),bounds);check(bounds.outWidth==1800,"OCR gets the source before card downsampling");
   Uri date=fixture(c,"Дата изготовления: 04.11.2026","Годен до: 14.11.2026","После вскрытия хранить 3 суток");fixtures.add(date);
   var purpose=EditorActivity.class.getDeclaredField("datePhoto");purpose.setAccessible(true);runOnMainSync(()->{try{purpose.setBoolean(form,true);}catch(Exception e){throw new RuntimeException(e);}form.onActivityResult(21,Activity.RESULT_OK,new Intent().setData(date));});
   until(()->ui(()->button(form,"Сохранить упаковку").isEnabled()),"date image was not imported");check(PhotoImages.source(c,id,false).isFile()&&PhotoImages.source(c,id,true).isFile(),"two inputs retained separately");
   runOnMainSync(()->{form.findViewById(R.id.ocr_local).performClick();form.findViewById(R.id.ocr_cancel).performClick();});
   check(ui(()->button(form,"Сохранить упаковку").isEnabled()&&((EditText)form.findViewById(R.id.product_name_input)).getText().length()==0),"cancelled job leaves fields untouched and releases the form");
   runOnMainSync(()->form.findViewById(R.id.ocr_local).performClick());check(ui(()->!button(form,"Сохранить упаковку").isEnabled()),"saving is blocked while OCR is running");
   until(()->ui(()->review(form)!=null&&review(form).isShowing()),"native Russian OCR did not reach the review dialog");AlertDialog draft=review(form);
   check(ui(()->choice(draft,"name")!=null&&choice(draft,"name").getText().toString().toUpperCase(Locale.ROOT).contains("МОЛОКО")),"native Tesseract reads a printed Russian name");
   check(ui(()->choice(draft,"quantity")!=null&&choice(draft,"quantity").getText().toString().contains("500")&&choice(draft,"unit").getText().toString().contains("мл")),"native OCR reads coupled volume");
   check(ui(()->choice(draft,"manufactured")!=null&&choice(draft,"manufactured").getText().toString().contains("2026-11-04")&&choice(draft,"expiry")!=null&&choice(draft,"expiry").getText().toString().contains("2026-11-14")),"native OCR distinguishes manufacture and expiry");
   check(ui(()->!choice(draft,"expiry").isChecked()&&!choice(draft,"manufactured").isChecked()),"dates are not selected automatically");
   check(ui(()->((EditText)form.findViewById(R.id.expiry_input)).getText().length()==0)&&store.packets().size()==packets,"recognition does not write form or ledger before confirmation");
   waitForIdleSync();Bitmap screenshot=getUiAutomation().takeScreenshot();if(screenshot!=null){try(OutputStream out=new FileOutputStream(new File(c.getCacheDir(),"ocr-review-test.png"))){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}screenshot.recycle();}
   runOnMainSync(()->{choice(draft,"expiry").setChecked(true);choice(draft,"manufactured").setChecked(true);draft.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});
   check(ui(()->((EditText)form.findViewById(R.id.expiry_input)).getText().toString().equals("2026-11-14")&&((EditText)form.findViewById(R.id.quantity_input)).getText().toString().equals("500")),"confirmed suggestions enter the editable form");check(store.packets().size()==packets,"confirming suggestions still does not save inventory");
   runOnMainSync(()->button(form,"Сохранить упаковку").performClick());until(()->inventory.products().stream().anyMatch(p->p.id.equals(id)),"ordinary save did not persist product");created=id;
   Product saved=store.products().stream().filter(p->p.id.equals(id)).findFirst().orElseThrow();check(saved.quantityMilli==500000&&saved.unit.equals("мл")&&saved.printedExpiry.equals(LocalDate.of(2026,11,14)),"recognized product saves through existing inventory model");
   byte[] bytes=android.util.Base64.decode(saved.photo,android.util.Base64.DEFAULT);Bitmap card=BitmapFactory.decodeByteArray(bytes,0,bytes.length);check(card.getWidth()<=800&&bytes.length<=170000,"card keeps compact primary photo");card.recycle();check(!PhotoImages.source(c,id,false).exists()&&!PhotoImages.source(c,id,true).exists(),"temporary high-resolution inputs removed after save");
   PhotoDraft bare=LabelParser.parse("14.11.2026");check(!bare.fields.containsKey("expiry")&&bare.unknownDates.size()==1,"unlabelled date requires manual role");
   JSONObject cloud=new JSONObject("{\"fields\":{\"expiry\":\"2026-02-31\"},\"evidence\":{\"expiry\":\"Годен до 31.02.2026\"}}");boolean rejected=false;try{PhotoCloudClient.parse(cloud);}catch(Exception e){rejected=true;}check(rejected,"Android rejects invalid cloud dates");
   check(PhotoCloudClient.origin("https://example.workers.dev/").equals("https://example.workers.dev"),"HTTPS origin accepted");rejected=false;try{PhotoCloudClient.origin("http://example.test");}catch(Exception e){rejected=true;}check(rejected,"HTTP cloud destination rejected");
   c.getSharedPreferences("photo-recognition",0).edit().putString("url",priorEndpoint).commit();
   result.putString("stream","PASS: "+checks+" Android photo checks: real native rus+eng OCR on synthetic printed labels, two inputs, draft-only result, explicit dates, encrypted inventory save, bounded card image, cache cleanup, cloud response validation\n");sendStatus(0,status());finish(Activity.RESULT_OK,result);
  }catch(Throwable e){String failure="FAIL after "+checks+" photo checks: "+e+"\n"+android.util.Log.getStackTraceString(e);result.putString("stream",failure);Bundle failed=status();failed.putString("stack",failure);sendStatus(-2,failed);finish(Activity.RESULT_CANCELED,result);
  }finally{if(editor!=null){EditorActivity f=editor;runOnMainSync(f::finish);}if(store!=null&&created!=null)try{store.change(created,Map.of("deleted","true"));}catch(Exception ignored){}for(Uri uri:fixtures)getTargetContext().getContentResolver().delete(uri,null,null);}
 }
}
