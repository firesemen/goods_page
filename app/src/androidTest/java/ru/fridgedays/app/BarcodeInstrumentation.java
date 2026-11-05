package ru.fridgedays.app;
import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.provider.MediaStore;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import java.util.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import ru.fridgedays.core.*;

public final class BarcodeInstrumentation extends Instrumentation {
 private int checks;private ActivityMonitor cameraMonitor;
 private Bundle status(){Bundle b=new Bundle();b.putString("id","InstrumentationTestRunner");b.putInt("numtests",1);b.putInt("current",1);b.putString("class",getClass().getName());b.putString("test","barcodeAndCamera");return b;}
 @Override public void onCreate(Bundle b){super.onCreate(b);start();}
 private void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 private boolean ui(BooleanSupplier c){boolean[] r={false};runOnMainSync(()->r[0]=c.getAsBoolean());return r[0];}
 private void until(BooleanSupplier c,String message)throws Exception{long end=System.nanoTime()+30_000_000_000L;while(!c.getAsBoolean()){if(System.nanoTime()>end)throw new AssertionError(message);Thread.sleep(100);}}
 private void views(View v,List<View> all){all.add(v);if(v instanceof ViewGroup g)for(int n=0;n<g.getChildCount();n++)views(g.getChildAt(n),all);}
 private Button button(Activity a,String text){List<View> all=new ArrayList<>();views(a.getWindow().getDecorView(),all);return all.stream().filter(v->v instanceof Button b&&b.getText().toString().equals(text)).map(v->(Button)v).findFirst().orElseThrow();}
 private AlertDialog review(EditorActivity a){try{var f=EditorActivity.class.getDeclaredField("reviewDialog");f.setAccessible(true);return (AlertDialog)f.get(a);}catch(Exception e){throw new RuntimeException(e);}}
 private String photo(EditorActivity a){try{var f=EditorActivity.class.getDeclaredField("photo");f.setAccessible(true);return (String)f.get(a);}catch(Exception e){throw new RuntimeException(e);}}
 private void scanFixture(BarcodeFormat type,String text,boolean rotate)throws Exception{
  BitMatrix matrix=new MultiFormatWriter().encode(text,type,type==BarcodeFormat.EAN_13?900:500,type==BarcodeFormat.EAN_13?300:500);int w=matrix.getWidth(),h=matrix.getHeight();byte[] bytes=new byte[w*h];for(int y=0;y<h;y++)for(int x=0;x<w;x++)bytes[y*w+x]=(byte)(matrix.get(x,y)?0:255);
  if(rotate){byte[] r=new byte[bytes.length];for(int y=0;y<h;y++)for(int x=0;x<w;x++)r[x*h+h-y-1]=bytes[y*w+x];bytes=r;int swap=w;w=h;h=swap;}
  Result result=BarcodeScanner.decode(bytes,w,h,true);check(Barcode.fromScan(result.getText()).equals(Barcode.canonical("4601234567893")),"ZXing decodes "+type+" rotated="+rotate);
 }
 private static final class Stub extends HttpURLConnection {
  String json;Map<String,String> headers=new HashMap<>();Stub(URL u,String json){super(u);this.json=json;}public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
  @Override public int getResponseCode(){return 200;}@Override public InputStream getInputStream(){return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));}@Override public void setRequestProperty(String k,String v){headers.put(k,v);}
 }
 @Override public void onStart(){sendStatus(1,status());Bundle result=new Bundle();Store store=null;String oldId=null,newId=null;EditorActivity editor=null;
 try{
  scanFixture(BarcodeFormat.EAN_13,"4601234567893",false);scanFixture(BarcodeFormat.EAN_13,"4601234567893",true);scanFixture(BarcodeFormat.QR_CODE,"https://id.gs1.org/01/04601234567893",false);scanFixture(BarcodeFormat.DATA_MATRIX,"010460123456789321serial",false);
  JSONObject external=new JSONObject("{\"status\":1,\"code\":\"4601234567893\",\"product\":{\"product_name_ru\":\"Молоко\",\"brands\":\"Тест\",\"quantity\":\"1,5 l\",\"expiry\":\"2026-01-01\"}}");
  PhotoDraft found=ProductLookup.parse(external,"4601234567893");check(found.fields.get("name").text.contains("Молоко")&&found.fields.get("quantity").text.equals("1.5")&&found.fields.get("unit").text.equals("л"),"OFF fields parsed with decimal units");check(!found.fields.containsKey("expiry"),"external expiry ignored");
  check(ProductLookup.parse(new JSONObject("{\"status\":0}"),"4601234567893")==null,"missing product fallback");boolean rejected=false;try{ProductLookup.parse(external,"96385074");}catch(Exception e){rejected=true;}check(rejected,"wrong external barcode rejected");
  Stub[] captured={null};new ProductLookup(url->captured[0]=new Stub(url,external.toString())).find("4601234567893");check(captured[0].getURL().getHost().equals("world.openfoodfacts.org")&&!captured[0].getInstanceFollowRedirects()&&!captured[0].headers.containsKey("Authorization"),"fixed HTTPS database origin without credentials/redirects");
  store=Store.get(getTargetContext());Store inventory=store;oldId=UUID.randomUUID().toString();String old=oldId;String gtin=Barcode.canonical("4601234567893");store.change(oldId,Map.of("name","Семейное молоко","initialQuantity","500","unit","мл","barcode",gtin,"expiry","2026-01-01","openingDays","7"));store.change(oldId,Map.of("delta","-250"));store.change(oldId,Map.of("deleted","true"));check(store.byBarcode(gtin).id.equals(oldId),"deleted family package remains usable as template");
  editor=(EditorActivity)startActivitySync(new Intent(getTargetContext(),EditorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));EditorActivity form=editor;waitForIdleSync();
  runOnMainSync(()->{((EditText)form.findViewById(R.id.barcode_input)).setText("4601234567893");form.findViewById(R.id.barcode_lookup).performClick();});until(()->ui(()->review(form)!=null&&review(form).isShowing()),"family result not shown");
  check(ui(()->review(form).getWindow().getDecorView().findViewWithTag("review-expiry")==null),"family result contains no expiry");runOnMainSync(()->review(form).getButton(AlertDialog.BUTTON_POSITIVE).performClick());check(ui(()->((EditText)form.findViewById(R.id.quantity_input)).getText().toString().equals("500")&&((EditText)form.findViewById(R.id.expiry_input)).getText().length()==0),"new pack uses full amount and blank expiry");
  store.change(oldId,Map.of("unit","л"));check(!store.barcodeDraft(store.byBarcode(gtin)).fields.containsKey("quantity"),"changed unit never reuses the old numeric pack size");
  cameraMonitor=new ActivityMonitor(){@Override public ActivityResult onStartActivity(Intent intent){if(MediaStore.ACTION_IMAGE_CAPTURE.equals(intent.getAction()))intent.setComponent(new ComponentName(getContext().getPackageName(),FakeCaptureActivity.class.getName()));return null;}};addMonitor(cameraMonitor);
  getUiAutomation().executeShellCommand("pm grant ru.fridgedays.app android.permission.CAMERA").close();
  runOnMainSync(()->button(form,"Сфотографировать").performClick());until(()->ui(()->!photo(form).isEmpty()&&button(form,"Сохранить упаковку").isEnabled()),"camera photo was not attached");check(true,"separate-UID camera wrote granted URI and returned no Intent");String front=photo(form);
  var productField=EditorActivity.class.getDeclaredField("product");productField.setAccessible(true);String product=(String)productField.get(form);
  check(PhotoImages.source(getTargetContext(),product,false).isFile(),"captured source available for Cloudflare");
  ActivityMonitor recreatedMonitor=addMonitor(EditorActivity.class.getName(),null,false);runOnMainSync(form::recreate);EditorActivity active=(EditorActivity)waitForMonitorWithTimeout(recreatedMonitor,30000);removeMonitor(recreatedMonitor);if(active==null)throw new AssertionError("editor was not recreated");editor=active;waitForIdleSync();
  check(ui(()->photo(active).equals(front)&&((EditText)active.findViewById(R.id.barcode_input)).getText().toString().equals(gtin)),"camera image and barcode survive editor recreation");
  runOnMainSync(()->button(active,"Снять срок крупно").performClick());until(()->PhotoImages.source(getTargetContext(),product,true).isFile()&&ui(()->button(active,"Сохранить упаковку").isEnabled()),"date capture not attached");check(photo(active).equals(front),"date capture preserves primary photo");
  runOnMainSync(()->((EditText)active.findViewById(R.id.expiry_input)).setText("2030-12-20"));runOnMainSync(()->button(active,"Сохранить упаковку").performClick());until(()->inventory.products().stream().anyMatch(p->p.id.equals(product)),"captured package not saved");newId=product;Product saved=store.products().stream().filter(p->p.id.equals(product)).findFirst().orElseThrow();check(saved.barcode.equals(gtin)&&!saved.photo.isEmpty()&&saved.quantityMilli==500000,"barcode and camera image persist in normal encrypted package");
  File folder=new File(getTargetContext().getCacheDir(),"capture");check(folder.listFiles()==null||folder.listFiles().length==0,"temporary capture files removed");
  boolean denied=false;try{CaptureProvider.file(getTargetContext(),Uri.parse("content://ru.fridgedays.app.capture/capture/../../private"));}catch(FileNotFoundException e){denied=true;}check(denied,"provider rejects paths outside capture namespace");
  result.putString("stream","PASS: "+checks+" barcode/camera checks: actual ZXing decoding, local history without dates, external contract fixtures, real cross-UID URI camera capture, date image and encrypted save. Live external database and physical camera not tested.\n");sendStatus(0,status());finish(Activity.RESULT_OK,result);
 }catch(Throwable e){String text="FAIL after "+checks+" barcode/camera checks: "+e+"\n"+android.util.Log.getStackTraceString(e);result.putString("stream",text);Bundle failed=status();failed.putString("stack",text);sendStatus(-2,failed);finish(Activity.RESULT_CANCELED,result);
 }finally{if(cameraMonitor!=null)removeMonitor(cameraMonitor);if(editor!=null){EditorActivity f=editor;runOnMainSync(f::finish);}if(store!=null)for(String id:new String[]{oldId,newId})if(id!=null)try{store.change(id,Map.of("deleted","true"));}catch(Exception ignored){}}
 }
}
