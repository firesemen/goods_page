package ru.fridgedays.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import ru.fridgedays.core.*;

/** End-to-end Android smoke checks, no third-party runner dependency. */
public final class SmokeInstrumentation extends Instrumentation {
    private int checks;
    private Bundle testStatus(){Bundle b=new Bundle();b.putString("id","InstrumentationTestRunner");b.putInt("numtests",1);b.putInt("current",1);b.putString("class",getClass().getName());b.putString("test","endToEnd");return b;}
    @Override public void onCreate(Bundle b){super.onCreate(b);start();}
    private void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    private void until(BooleanSupplier condition)throws Exception{long end=System.currentTimeMillis()+15_000;while(!condition.getAsBoolean()){if(System.currentTimeMillis()>end)throw new AssertionError("Timed out waiting for Android operation");Thread.sleep(80);}}
    private Product product(Store store,String id){return store.products().stream().filter(p->p.id.equals(id)).findFirst().orElseThrow();}
    private void views(View view,List<View> out){out.add(view);if(view instanceof ViewGroup g)for(int n=0;n<g.getChildCount();n++)views(g.getChildAt(n),out);}
    private List<View> views(Activity a){List<View> list=new ArrayList<>();views(a.getWindow().getDecorView(),list);return list;}
    private Button button(Activity a,String text){return views(a).stream().filter(v->v instanceof Button b&&b.getText().toString().equals(text)).map(v->(Button)v).findFirst().orElseThrow();}
    @Override public void onStart(){Bundle result=new Bundle();sendStatus(1,testStatus());try{
        Context context=getTargetContext();Store store=Store.get(context);Invitation invitation=store.invitation();LocalDate today=ReminderReceiver.today(context);
        String id=UUID.randomUUID().toString();store.change(id,Map.of("name","Проверка сосиски","initialQuantity","3","unit","шт.","expiry",today.plusDays(10).toString()));
        check(product(store,id).quantity==3,"create persists locally");
        Map<String,String> encrypted=store.packets();check(encrypted.values().stream().noneMatch(s->s.contains("сосиски")),"DB contains encrypted payloads");
        check(Invitation.parse(invitation.link()).room().equals(invitation.room()),"invitation works on Android");
        store.change(id,Map.of("opened",today.toString()));check(product(store,id).expiry.equals(today.plusDays(3)),"opening date");
        store.change(id,Map.of("opened",""));check(product(store,id).expiry.equals(today.plusDays(10)),"undo opening");
        Ledger other=new Ledger();for(var e:store.packets().entrySet())other.merge(List.of(Codec.operation(Crypto.open(invitation.key,invitation.context(e.getKey()),e.getValue()))));
        Operation remote=other.change(UUID.randomUUID().toString(),id,Map.of("delta","-1"));String sealed=Crypto.seal(invitation.key,invitation.context(remote.id),Codec.operation(remote));
        store.change(id,Map.of("delta","-1"));store.merge(Map.of(remote.id,sealed));store.merge(Map.of(remote.id,sealed));check(product(store,id).quantity==1,"offline actions converge and replay deduplicates");
        byte[] damaged=Crypto.decode(sealed);damaged[damaged.length-1]^=1;boolean rejected=false;try{store.merge(Map.of(remote.id,Crypto.encode(damaged)));}catch(Exception e){rejected=true;}check(rejected&&product(store,id).quantity==1,"tampered ciphertext rejected atomically");
        char[] password="instrumentation-only-password".toCharArray();Backup backup=new Backup(invitation,store.packets());Backup restored=Backup.parse(Crypto.passwordOpen(password,Crypto.passwordSeal(password,backup.bytes())));check(restored.packets.equals(store.packets()),"password backup roundtrip on Android");

        // Actual native form, actual MediaStore URI, ImageDecoder compression, asynchronous save.
        String yogurtName="Проверка йогурт "+UUID.randomUUID().toString().substring(0,8);
        EditorActivity editor=(EditorActivity)startActivitySync(new Intent(context,EditorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        runOnMainSync(()->{((EditText)editor.findViewById(R.id.product_name_input)).setText(yogurtName);((EditText)editor.findViewById(R.id.quantity_input)).setText("2");((EditText)editor.findViewById(R.id.expiry_input)).setText(today.toString());});
        ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,"fridge-test.jpg");values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");Uri uri=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
        Bitmap bitmap=Bitmap.createBitmap(1600,1200,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.GREEN);try(OutputStream out=context.getContentResolver().openOutputStream(uri)){bitmap.compress(Bitmap.CompressFormat.JPEG,95,out);}bitmap.recycle();
        runOnMainSync(()->editor.onActivityResult(21,Activity.RESULT_OK,new Intent().setData(uri)));
        until(()->{final boolean[] ready={false};runOnMainSync(()->ready[0]=button(editor,"Сохранить упаковку").isEnabled());return ready[0];});
        runOnMainSync(()->button(editor,"Сохранить упаковку").performClick());until(()->store.products().stream().anyMatch(p->p.name.equals(yogurtName)));
        Product yogurt=store.products().stream().filter(p->p.name.equals(yogurtName)).findFirst().orElseThrow();check(!yogurt.photo.isEmpty(),"photo stored through native editor");
        byte[] photo=android.util.Base64.decode(yogurt.photo,android.util.Base64.DEFAULT);Bitmap decoded=BitmapFactory.decodeByteArray(photo,0,photo.length);check(Math.max(decoded.getWidth(),decoded.getHeight())<=800&&photo.length<=170_000,"photo dimensions and size bounded");decoded.recycle();context.getContentResolver().delete(uri,null,null);

        MainActivity main=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();until(()->{final boolean[] found={false};runOnMainSync(()->found[0]=views(main).stream().anyMatch(v->v instanceof TextView t&&t.getText().toString().equals(yogurtName)));return found[0];});check(true,"main screen renders saved card");
        NotificationManager notifications=context.getSystemService(NotificationManager.class);notifications.cancel(19);
        until(()->Arrays.stream(notifications.getActiveNotifications()).noneMatch(n->n.getId()==19));
        ReminderReceiver.schedule(context);ReminderReceiver.send(context,store,true);
        // notify() queues work in system_server; verify its actual delivery after that queue runs.
        until(()->Arrays.stream(notifications.getActiveNotifications()).anyMatch(n->n.getId()==19));
        check(Arrays.stream(notifications.getActiveNotifications()).anyMatch(n->n.getId()==19),"local notification posted");

        // Stop Activity's listener, then exercise the real socket server and encryption framing.
        runOnMainSync(main::finish);waitForIdleSync();try(NearbySync nearby=new NearbySync(context,store,s->{})){
            java.lang.reflect.Field field=NearbySync.class.getDeclaredField("server");field.setAccessible(true);ServerSocket server=(ServerSocket)field.get(nearby);
            try(Socket socket=new Socket("127.0.0.1",server.getLocalPort())){socket.setSoTimeout(10_000);var out=new DataOutputStream(socket.getOutputStream());var in=new DataInputStream(socket.getInputStream());
                write(out,invitation,"manifest",Map.of());Map<String,String> response=read(in,invitation,"response");check(response.keySet().equals(store.packets().keySet())&&response.containsKey(remote.id),"real LAN server returns missing encrypted operation IDs");
                Ledger lanReplica=new Ledger();for(var entry:store.packets().entrySet())lanReplica.merge(List.of(Codec.operation(Crypto.open(invitation.key,invitation.context(entry.getKey()),entry.getValue()))));
                Operation lan=lanReplica.change(UUID.randomUUID().toString(),id,Map.of("name","Проверка LAN"));String payload=Crypto.seal(invitation.key,invitation.context(lan.id),Codec.operation(lan));write(out,invitation,"push",Map.of(lan.id,payload));read(in,invitation,"ack");check(product(store,id).name.equals("Проверка LAN"),"real LAN socket applies authenticated remote operation");
            }
        }
        store.change(id,Map.of("deleted","true"));store.change(yogurt.id,Map.of("deleted","true"));
        result.putString("stream","PASS: "+checks+" Android checks: Keystore, encrypted SQLite, dates, merge, backup, native form, photo, list, notification, LAN sockets\n");result.putInt("checks",checks);sendStatus(0,testStatus());finish(Activity.RESULT_OK,result);
    }catch(Throwable e){String failure="FAIL after "+checks+" checks: "+e+"\n"+android.util.Log.getStackTraceString(e);result.putString("stream",failure);Bundle failed=testStatus();failed.putString("stack",failure);sendStatus(-2,failed);finish(Activity.RESULT_CANCELED,result);}}
    private void write(DataOutputStream out,Invitation i,String stage,Map<String,String> packets)throws Exception{byte[] b=Crypto.seal(i.key,i.context("nearby-"+stage),Codec.packets(packets)).getBytes(java.nio.charset.StandardCharsets.US_ASCII);out.writeInt(b.length);out.write(b);out.flush();}
    private Map<String,String> read(DataInputStream in,Invitation i,String stage)throws Exception{int n=in.readInt();if(n<0||n>3_000_000)throw new IOException("frame");byte[] b=new byte[n];in.readFully(b);return Codec.packets(Crypto.open(i.key,i.context("nearby-"+stage),new String(b,java.nio.charset.StandardCharsets.US_ASCII)));}
}
