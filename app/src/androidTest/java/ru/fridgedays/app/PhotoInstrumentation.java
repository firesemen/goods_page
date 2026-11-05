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
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;
import ru.fridgedays.core.*;

/** Cloud HTTP contract with a stub connection, real Android photo processing/review/save. No AI inference is claimed. */
public final class PhotoInstrumentation extends Instrumentation {
 private int checks;
 private Bundle status(){Bundle b=new Bundle();b.putString("id","InstrumentationTestRunner");b.putInt("numtests",1);b.putInt("current",1);b.putString("class",getClass().getName());b.putString("test","cloudPhotoDraft");return b;}
 @Override public void onCreate(Bundle b){super.onCreate(b);start();}
 private void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
 private boolean ui(BooleanSupplier call){boolean[] b={false};runOnMainSync(()->b[0]=call.getAsBoolean());return b[0];}
 private void until(BooleanSupplier ready,String message)throws Exception{long end=System.nanoTime()+30_000_000_000L;while(!ready.getAsBoolean()){if(System.nanoTime()>end)throw new AssertionError(message);Thread.sleep(100);}}
 private Button button(Activity a,String label){List<View> all=new ArrayList<>();collect(a.getWindow().getDecorView(),all);return all.stream().filter(v->v instanceof Button b&&b.getText().toString().equals(label)).map(v->(Button)v).findFirst().orElseThrow();}
 private void collect(View v,List<View> all){all.add(v);if(v instanceof ViewGroup group)for(int n=0;n<group.getChildCount();n++)collect(group.getChildAt(n),all);}
 private CheckBox choice(AlertDialog d,String key){return d.getWindow().getDecorView().findViewWithTag("review-"+key);}
 private Uri fixture(Context c,int color)throws Exception{
  ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,"fridge-cloud-test.jpg");values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");Uri uri=c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
  Bitmap image=Bitmap.createBitmap(1800,1000,Bitmap.Config.ARGB_8888);image.eraseColor(color);try(OutputStream out=c.getContentResolver().openOutputStream(uri)){image.compress(Bitmap.CompressFormat.JPEG,95,out);}image.recycle();return uri;
 }
 private static final class StubConnection extends HttpURLConnection {
  int code=200;boolean disconnected,timeout;String json;ByteArrayOutputStream sent=new ByteArrayOutputStream();Map<String,String> headers=new HashMap<>();
  StubConnection(URL url,String json){super(url);this.json=json;}
  public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){disconnected=true;}
  @Override public void setRequestProperty(String key,String value){headers.put(key,value);}
  @Override public OutputStream getOutputStream(){return sent;}
  @Override public int getResponseCode()throws IOException{if(timeout)throw new SocketTimeoutException();return code;}
  @Override public InputStream getInputStream(){return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));}
  @Override public InputStream getErrorStream(){return getInputStream();}
 }
 @Override public void onStart(){sendStatus(1,status());Bundle result=new Bundle();EditorActivity editor=null;Store store=null;String created=null;List<Uri> fixtures=new ArrayList<>();String priorEndpoint=null;
  try{
   Context c=getTargetContext();File legacy=new File(c.getFilesDir(),"ocr/tessdata");legacy.mkdirs();Files.write(new File(legacy,"rus.traineddata").toPath(),new byte[]{1});Files.write(new File(legacy,"eng.traineddata.tmp").toPath(),new byte[]{1});
   store=Store.get(c);Store inventory=store;check(!new File(c.getFilesDir(),"ocr").exists(),"upgrade removes installed legacy dictionaries");
   int packets=store.packets().size();Invitation family=store.invitation();priorEndpoint=PhotoCloudClient.endpoint(c);c.getSharedPreferences("photo-recognition",0).edit().remove("url").commit();
   boolean absent=false;try{Class.forName("com.googlecode.tesseract.android.TessBaseAPI");}catch(ClassNotFoundException e){absent=true;}check(absent,"native OCR runtime is absent");
   absent=false;try(InputStream ignored=c.getAssets().open("tessdata/rus.traineddata")){}catch(FileNotFoundException e){absent=true;}check(absent,"APK does not bundle OCR dictionaries");
   editor=(EditorActivity)startActivitySync(new Intent(c,EditorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));EditorActivity form=editor;waitForIdleSync();
   check(ui(()->{List<View> all=new ArrayList<>();collect(form.getWindow().getDecorView(),all);return all.stream().noneMatch(v->v instanceof TextView t&&t.getText().toString().equals("Распознать на телефоне"));}),"editor has no local-recognition button");
   Uri front=fixture(c,Color.GREEN);fixtures.add(front);runOnMainSync(()->form.onActivityResult(21,Activity.RESULT_OK,new Intent().setData(front)));until(()->ui(()->button(form,"Сохранить упаковку").isEnabled()),"front was not imported");
   var productField=EditorActivity.class.getDeclaredField("product");productField.setAccessible(true);String id=(String)productField.get(form);
   Uri date=fixture(c,Color.WHITE);fixtures.add(date);var purpose=EditorActivity.class.getDeclaredField("datePhoto");purpose.setAccessible(true);runOnMainSync(()->{try{purpose.setBoolean(form,true);}catch(Exception e){throw new RuntimeException(e);}form.onActivityResult(21,Activity.RESULT_OK,new Intent().setData(date));});until(()->ui(()->button(form,"Сохранить упаковку").isEnabled()),"date was not imported");
   check(PhotoImages.source(c,id,false).isFile()&&PhotoImages.source(c,id,true).isFile(),"two photo inputs remain separate");check(store.packets().size()==packets,"photo import neither saves product nor uploads automatically");
   String payload="{\"fields\":{\"name\":\"Молоко\",\"quantity\":\"500\",\"unit\":\"мл\",\"manufactured\":\"2026-11-04\",\"expiry\":\"2026-11-14\"},\"evidence\":{\"name\":\"Молоко\",\"quantity\":\"500 мл\",\"unit\":\"500 мл\",\"manufactured\":\"Изготовлено 04.11.2026\",\"expiry\":\"Годен до 14.11.2026\"},\"unknownDates\":[],\"notes\":[],\"recognizedText\":\"Молоко 500 мл\"}";
   StubConnection[] captured={null};PhotoCloudClient client=new PhotoCloudClient(url->captured[0]=new StubConnection(url,payload));
   PhotoDraft cloud=client.recognize("https://photo.example",family,List.of(PhotoImages.source(c,id,false),PhotoImages.source(c,id,true)));StubConnection http=captured[0];
   check(http.getURL().toString().equals("https://photo.example/v1/rooms/"+family.room()+"/recognize")&&http.getRequestMethod().equals("POST"),"cloud client uses the family recognition endpoint");
   check(http.headers.get("Authorization").equals("Bearer "+Crypto.photoCapability(family.key,family.room()))&&!http.getInstanceFollowRedirects(),"separate capability is sent without following redirects");
   JSONObject body=new JSONObject(new String(http.sent.toByteArray(),StandardCharsets.UTF_8));JSONArray images=body.getJSONArray("images");check(images.length()==2&&http.sent.size()<3_000_000,"two bounded photos in request");
   byte[] sent=android.util.Base64.decode(images.getString(0),android.util.Base64.DEFAULT);Bitmap sentImage=BitmapFactory.decodeByteArray(sent,0,sent.length);check(sentImage.getWidth()<=1600&&sent.length<=900000,"upload JPEG is bounded before sending");sentImage.recycle();
   check(http.disconnected&&store.packets().size()==packets,"client closes connection and only returns an uncommitted draft");
   PhotoCloudClient statusClient=new PhotoCloudClient(url->captured[0]=new StubConnection(url,"{\"service\":\"fridge-photo\",\"version\":2,\"ready\":true,\"dailyLimit\":20,\"remaining\":19}"));String status=statusClient.connectionStatus("https://photo.example",family);
   check(status.contains("19 из 20")&&captured[0].getRequestMethod().equals("GET")&&captured[0].sent.size()==0&&captured[0].getURL().getPath().endsWith("/status"),"connection check sends no image or AI request");
   int[] opened={0};PhotoCloudClient cancelled=new PhotoCloudClient(url->{opened[0]++;return new StubConnection(url,payload);});cancelled.cancel();boolean rejected=false;try{cancelled.connectionStatus("https://photo.example",family);}catch(java.util.concurrent.CancellationException e){rejected=true;}check(rejected&&opened[0]==0,"cancelled operation opens no connection");
   PhotoCloudClient unauthorized=new PhotoCloudClient(url->{StubConnection conn=new StubConnection(url,"{\"error\":\"unauthorized\"}");conn.code=401;return conn;});rejected=false;try{unauthorized.connectionStatus("https://photo.example",family);}catch(IOException e){rejected=e.getMessage().contains("ROOMS");}check(rejected,"wrong family authorization has a useful diagnostic");
   PhotoCloudClient timeout=new PhotoCloudClient(url->{opened[0]++;StubConnection conn=new StubConnection(url,"{}");conn.timeout=true;return conn;});rejected=false;try{timeout.connectionStatus("https://photo.example",family);}catch(IOException e){rejected=e.getMessage().contains("не успел");}check(rejected&&opened[0]==1,"timeout does not retry the request");
   rejected=false;try{PhotoCloudClient.parse(new JSONObject("{\"fields\":{\"expiry\":\"2026-02-31\"},\"evidence\":{\"expiry\":\"31.02.2026\"}}"));}catch(Exception e){rejected=true;}check(rejected,"invalid cloud date is rejected");
   rejected=false;try{PhotoCloudClient.origin("http://photo.example");}catch(Exception e){rejected=true;}check(rejected,"unencrypted cloud destination is rejected");
   AlertDialog[] review={null};var apply=EditorActivity.class.getDeclaredMethod("applySuggestions",Map.class);apply.setAccessible(true);runOnMainSync(()->review[0]=PhotoReviewDialog.show(form,cloud,fields->{try{apply.invoke(form,fields);}catch(Exception e){throw new RuntimeException(e);}}));AlertDialog draft=review[0];
   check(ui(()->!choice(draft,"expiry").isChecked()&&!choice(draft,"manufactured").isChecked()),"cloud dates require explicit confirmation");check(ui(()->((EditText)form.findViewById(R.id.expiry_input)).getText().length()==0)&&store.packets().size()==packets,"review has not changed form or ledger");
   runOnMainSync(()->{choice(draft,"expiry").setChecked(true);draft.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});check(ui(()->((EditText)form.findViewById(R.id.expiry_input)).getText().toString().equals("2026-11-14"))&&store.packets().size()==packets,"only selected cloud suggestions enter the form");
   runOnMainSync(()->button(form,"Сохранить упаковку").performClick());until(()->inventory.products().stream().anyMatch(p->p.id.equals(id)),"save did not persist product");created=id;
   Product saved=store.products().stream().filter(p->p.id.equals(id)).findFirst().orElseThrow();check(saved.quantityMilli==500000&&saved.unit.equals("мл")&&saved.printedExpiry.equals(LocalDate.of(2026,11,14)),"cloud suggestions save through the existing encrypted inventory");
   byte[] bytes=android.util.Base64.decode(saved.photo,android.util.Base64.DEFAULT);Bitmap card=BitmapFactory.decodeByteArray(bytes,0,bytes.length);check(card.getWidth()<=800&&bytes.length<=170000,"card keeps compact primary photo");card.recycle();check(!PhotoImages.source(c,id,false).exists()&&!PhotoImages.source(c,id,true).exists(),"temporary inputs removed after save");
   result.putString("stream","PASS: "+checks+" Android cloud-photo checks: HTTP contract with stub connection, no local engine/models, legacy cleanup, two inputs, explicit date confirmation, encrypted save. Real Cloudflare AI inference is not tested.\n");sendStatus(0,status());finish(Activity.RESULT_OK,result);
  }catch(Throwable e){String failure="FAIL after "+checks+" cloud-photo checks: "+e+"\n"+android.util.Log.getStackTraceString(e);result.putString("stream",failure);Bundle failed=status();failed.putString("stack",failure);sendStatus(-2,failed);finish(Activity.RESULT_CANCELED,result);
  }finally{if(priorEndpoint!=null)getTargetContext().getSharedPreferences("photo-recognition",0).edit().putString("url",priorEndpoint).commit();if(editor!=null){EditorActivity f=editor;runOnMainSync(f::finish);}if(store!=null&&created!=null)try{store.change(created,Map.of("deleted","true"));}catch(Exception ignored){}for(Uri uri:fixtures)getTargetContext().getContentResolver().delete(uri,null,null);}
 }
}
