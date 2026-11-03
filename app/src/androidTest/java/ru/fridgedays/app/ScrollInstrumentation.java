package ru.fridgedays.app;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BooleanSupplier;
import ru.fridgedays.core.*;

/** Regression checks on a genuinely scrolled long list, including sorted-row movement. */
public final class ScrollInstrumentation extends Instrumentation {
    private volatile MainActivity screen;
    private int checks;
    private Bundle status(){Bundle b=new Bundle();b.putString("id","InstrumentationTestRunner");b.putInt("numtests",1);b.putInt("current",1);b.putString("class",getClass().getName());b.putString("test","preserveViewportAfterActions");return b;}
    @Override public void onCreate(Bundle b){super.onCreate(b);start();}
    private void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private boolean ui(BooleanSupplier condition){boolean[] value={false};runOnMainSync(()->value[0]=condition.getAsBoolean());return value[0];}
    private void until(BooleanSupplier ready,String message)throws Exception{long end=System.nanoTime()+30_000_000_000L;while(!ready.getAsBoolean()){if(System.nanoTime()>end)throw new AssertionError(message);Thread.sleep(80);}}
    private ScrollView scroll(){return screen.findViewById(R.id.page_scroll);}
    private View card(String id){return screen.getWindow().getDecorView().findViewWithTag("product:"+id);}
    private int top(View view){int y=0;for(View v=view;v!=scroll();v=(View)v.getParent())y+=v.getTop();return y;}
    private int offset(String id){return top(card(id))-scroll().getScrollY();}
    private void nav(String text){LinearLayout nav=screen.findViewById(R.id.bottom_nav);for(int n=0;n<nav.getChildCount();n++)if(((Button)nav.getChildAt(n)).getText().toString().equals(text)){nav.getChildAt(n).performClick();return;}throw new AssertionError("Missing navigation item");}
    private Product product(Store s,String id){return s.products().stream().filter(p->p.id.equals(id)).findFirst().orElseThrow();}
    private AccessibilityNodeInfo node(String id){AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root==null)return null;List<AccessibilityNodeInfo> all=root.findAccessibilityNodeInfosByViewId(id);return all.isEmpty()?null:all.get(0);}
    private void keepOffset(String id,int expected)throws Exception{until(()->ui(()->card(id)!=null&&scroll().getHeight()>0&&scroll().getScrollY()>0&&Math.abs(offset(id)-expected)<=2),"Product moved in the viewport after refresh");}
    private void button(String product,String label){ViewGroup group=(ViewGroup)card(product);List<View> all=new ArrayList<>();collect(group,all);all.stream().filter(v->v instanceof Button b&&b.getText().toString().equals(label)).findFirst().orElseThrow().performClick();}
    private boolean containsText(View v,String text){if(v==null)return false;List<View> all=new ArrayList<>();collect(v,all);return all.stream().anyMatch(child->child instanceof TextView t&&t.getText().toString().equals(text));}
    private void collect(View v,List<View> all){all.add(v);if(v instanceof ViewGroup g)for(int n=0;n<g.getChildCount();n++)collect(g.getChildAt(n),all);}
    @Override public void onStart(){sendStatus(1,status());Bundle result=new Bundle();Store store=null;List<String> created=new ArrayList<>();
        try{
            var service=getUiAutomation().getServiceInfo();service.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;getUiAutomation().setServiceInfo(service);
            Store state=Store.get(getTargetContext());store=state;LocalDate today=ReminderReceiver.today(getTargetContext());
            for(int n=0;n<12;n++){String id=UUID.randomUUID().toString();created.add(id);state.change(id,Map.of("name","Прокрутка "+n,"initialQuantity","3","unit","шт","expiry",today.plusDays(100+n).toString()));}
            String target=created.get(6);screen=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
            until(()->ui(()->scroll()!=null&&card(target)!=null&&scroll().getHeight()>0),"Long list did not render");
            runOnMainSync(()->scroll().scrollTo(0,top(card(target))+40));until(()->ui(()->scroll().getScrollY()>1000),"Test did not scroll down the list");int[] saved={0,0};runOnMainSync(()->{saved[0]=scroll().getScrollY();saved[1]=offset(target);});
            check(saved[0]>1000,"test begins deep inside long list");
            ScrollView[] before={null};runOnMainSync(()->{before[0]=scroll();((Spinner)card(target).findViewById(R.id.opening_days_selector)).setSelection(6);});until(()->product(state,target).openingDays==7,"Duration was not saved");until(()->ui(()->scroll()!=before[0]),"Dropdown did not refresh the view");keepOffset(target,saved[1]);
            check(ui(()->Math.abs(scroll().getScrollY()-saved[0])<=2),"duration dropdown preserves scroll position");
            runOnMainSync(()->{before[0]=scroll();card(target).findViewById(R.id.card_open).performClick();});until(()->product(state,target).opened!=null,"Open did not complete");until(()->ui(()->scroll()!=before[0]),"Open did not refresh the view");keepOffset(target,saved[1]);
            check(ui(()->offset(target)==saved[1]),"opening keeps the acted-on card anchored after date sorting");
            until(()->ui(()->((Button)card(target).findViewById(R.id.card_open)).getText().toString().equals("Отменить открытие")),"Open button did not refresh");runOnMainSync(()->card(target).findViewById(R.id.card_open).performClick());until(()->product(state,target).opened==null,"Undo did not complete");keepOffset(target,saved[1]);
            check(ui(()->Math.abs(scroll().getScrollY()-saved[0])<=2),"undo restores sorted card without jumping to page start");

            runOnMainSync(()->card(target).findViewById(R.id.card_consume).performClick());until(()->node("ru.fridgedays.app:id/consume_slider")!=null,"Consumption slider not visible");Bundle value=new Bundle();value.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,2);check(node("ru.fridgedays.app:id/consume_slider").performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),value),"remaining quantity adjusted through real slider");
            until(()->node("android:id/button1")!=null&&node("android:id/button1").isEnabled(),"Consumption confirmation not enabled");node("android:id/button1").performAction(AccessibilityNodeInfo.ACTION_CLICK);until(()->product(state,target).quantityMilli==2000,"Consumption did not save");keepOffset(target,saved[1]);check(ui(()->Math.abs(scroll().getScrollY()-saved[0])<=2),"consumption confirmation preserves list position");

            ActivityMonitor editorMonitor=addMonitor(EditorActivity.class.getName(),null,false);runOnMainSync(()->button(target,"Изменить"));EditorActivity editor=(EditorActivity)editorMonitor.waitForActivityWithTimeout(20_000);removeMonitor(editorMonitor);if(editor==null)throw new AssertionError("Editor did not open");
            runOnMainSync(()->{((EditText)editor.findViewById(R.id.product_name_input)).setText("Изменённый продукт");List<View> all=new ArrayList<>();collect(editor.getWindow().getDecorView(),all);all.stream().filter(v->v instanceof Button b&&b.getText().toString().equals("Сохранить упаковку")).findFirst().orElseThrow().performClick();});until(()->product(state,target).name.equals("Изменённый продукт"),"Editor did not save");until(()->ui(()->screen.hasWindowFocus()&&containsText(card(target),"Изменённый продукт")),"Editor did not return to the updated list");keepOffset(target,saved[1]);check(ui(()->Math.abs(scroll().getScrollY()-saved[0])<=2),"return from editor survives consecutive lifecycle refreshes");

            runOnMainSync(()->nav("Семья"));until(()->ui(()->screen.findViewById(R.id.add_product)==null&&scroll().getHeight()>0),"Family page did not open");runOnMainSync(()->scroll().scrollTo(0,100));runOnMainSync(()->nav("Холодильник"));keepOffset(target,saved[1]);check(ui(()->Math.abs(scroll().getScrollY()-saved[0])<=2),"each page retains its own scroll position");
            runOnMainSync(()->screen.findViewById(R.id.filter_all).performClick());keepOffset(target,saved[1]);check(ui(()->Math.abs(scroll().getScrollY()-saved[0])<=2),"reselecting current filter does not reset scroll");

            ActivityMonitor recreated=addMonitor(MainActivity.class.getName(),null,false);runOnMainSync(()->screen.recreate());MainActivity replacement=(MainActivity)recreated.waitForActivityWithTimeout(20_000);removeMonitor(recreated);if(replacement==null)throw new AssertionError("Recreation did not complete");screen=replacement;keepOffset(target,saved[1]);check(ui(()->Math.abs(scroll().getScrollY()-saved[0])<=2),"activity recreation restores scroll and anchor");

            java.lang.reflect.Field peerField=MainActivity.class.getDeclaredField("nearby");peerField.setAccessible(true);until(()->{try{return peerField.get(screen)!=null;}catch(Exception e){return false;}},"Nearby listener did not start");NearbySync peer=(NearbySync)peerField.get(screen);java.lang.reflect.Field serverField=NearbySync.class.getDeclaredField("server");serverField.setAccessible(true);ServerSocket server=(ServerSocket)serverField.get(peer);
            Invitation invitation=state.invitation();String remoteProduct=UUID.randomUUID().toString();created.add(remoteProduct);Operation remote=new Operation(UUID.randomUUID().toString(),UUID.randomUUID().toString(),remoteProduct,10000,Map.of("name","Получено по сети","initialQuantity","1","unit","шт","expiry",today.plusDays(1).toString()));String encrypted=Crypto.seal(invitation.key,invitation.context(remote.id),Codec.operation(remote));
            try(Socket socket=new Socket("127.0.0.1",server.getLocalPort())){socket.setSoTimeout(15_000);DataOutputStream out=new DataOutputStream(socket.getOutputStream());DataInputStream in=new DataInputStream(socket.getInputStream());Map<String,String> known=new TreeMap<>();state.packets().keySet().forEach(k->known.put(k,""));write(out,invitation,"manifest",known);read(in,invitation,"response");write(out,invitation,"push",Map.of(remote.id,encrypted));read(in,invitation,"ack");}
            until(()->state.products().stream().anyMatch(p->p.id.equals(remoteProduct)),"Network change did not arrive");until(()->ui(()->card(remoteProduct)!=null),"Incoming product did not appear in the UI");keepOffset(target,saved[1]);check(ui(()->offset(target)==saved[1]),"incoming network update preserves visible card after insertion above it");

            runOnMainSync(()->button(target,"Удалить"));until(()->node("android:id/button1")!=null,"Delete confirmation not visible");node("android:id/button1").performAction(AccessibilityNodeInfo.ACTION_CLICK);until(()->product(state,target).deleted,"Delete did not save");until(()->ui(()->card(target)==null&&scroll().getScrollY()>0),"Delete jumped to the page start");check(ui(()->scroll().getScrollY()>0),"deletion keeps a valid nonzero position");
            runOnMainSync(screen::finish);waitForIdleSync();for(String id:created)state.change(id,Map.of("deleted","true"));created.clear();
            result.putString("stream","PASS: "+checks+" scroll checks on long list, editing, sorted rows, navigation, recreation and actual socket update\n");result.putInt("checks",checks);sendStatus(0,status());finish(Activity.RESULT_OK,result);
        }catch(Throwable e){String message="FAIL after "+checks+" scroll checks: "+e+"\n"+android.util.Log.getStackTraceString(e);Bundle failure=status();failure.putString("stack",message);sendStatus(-2,failure);result.putString("stream",message);finish(Activity.RESULT_CANCELED,result);
        }finally{if(store!=null)for(String id:created)try{store.change(id,Map.of("deleted","true"));}catch(Exception ignored){}}
    }
    private void write(DataOutputStream out,Invitation i,String stage,Map<String,String> values)throws Exception{byte[] bytes=Crypto.seal(i.key,i.context("nearby-"+stage),Codec.packets(values)).getBytes(StandardCharsets.US_ASCII);out.writeInt(bytes.length);out.write(bytes);out.flush();}
    private void read(DataInputStream in,Invitation i,String stage)throws Exception{int n=in.readInt();if(n<0||n>3_000_000)throw new IOException("Invalid frame");byte[] bytes=new byte[n];in.readFully(bytes);Codec.packets(Crypto.open(i.key,i.context("nearby-"+stage),new String(bytes,StandardCharsets.US_ASCII)));}
}
