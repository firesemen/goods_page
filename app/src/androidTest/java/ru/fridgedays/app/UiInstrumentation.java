package ru.fridgedays.app;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BooleanSupplier;
import ru.fridgedays.core.*;

/** Functional UI acceptance checks for the 0.2 update. */
public final class UiInstrumentation extends Instrumentation {
    private int checks;
    private Bundle status(){Bundle b=new Bundle();b.putString("id","InstrumentationTestRunner");b.putInt("numtests",1);b.putInt("current",1);b.putString("class",getClass().getName());b.putString("test","updatedProductControls");return b;}
    @Override public void onCreate(Bundle b){super.onCreate(b);start();}
    private void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private void until(BooleanSupplier ready)throws Exception{long limit=System.nanoTime()+30_000_000_000L;while(!ready.getAsBoolean()){if(System.nanoTime()>limit)throw new AssertionError("UI operation timed out");Thread.sleep(80);}}
    private boolean ui(BooleanSupplier condition){boolean[] value={false};runOnMainSync(()->value[0]=condition.getAsBoolean());return value[0];}
    private List<View> views(Activity a){List<View> result=new ArrayList<>();collect(a.getWindow().getDecorView(),result);return result;}
    private void collect(View v,List<View> out){out.add(v);if(v instanceof ViewGroup g)for(int n=0;n<g.getChildCount();n++)collect(g.getChildAt(n),out);}
    private View card(Activity a,String id){return views(a).stream().filter(v->("product:"+id).equals(v.getTag())).findFirst().orElseThrow();}
    private Product product(Store s,String id){return s.products().stream().filter(p->p.id.equals(id)).findFirst().orElseThrow();}
    private void select(Spinner s,String item){for(int n=0;n<s.getCount();n++)if(s.getItemAtPosition(n).equals(item)){s.setSelection(n);return;}throw new AssertionError("Missing unit: "+item);}
    private AccessibilityNodeInfo node(String id){AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root==null)return null;List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByViewId(id);return nodes.isEmpty()?null:nodes.get(0);}
    private void setProgress(int value)throws Exception{String id="ru.fridgedays.app:id/consume_slider";until(()->node(id)!=null);Bundle b=new Bundle();b.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,value);check(node(id).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),b),"native remaining slider accepts user adjustment");}
    private void confirmConsumption()throws Exception{until(()->{AccessibilityNodeInfo n=node("android:id/button1");return n!=null&&n.isEnabled();});check(node("android:id/button1").performAction(AccessibilityNodeInfo.ACTION_CLICK),"native consumption confirmation works");}
    @Override public void onStart(){sendStatus(1,status());Bundle result=new Bundle();Store store=null;List<String> created=new ArrayList<>();
        try{
            android.accessibilityservice.AccessibilityServiceInfo info=getUiAutomation().getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;getUiAutomation().setServiceInfo(info);
            Store state=Store.get(getTargetContext());store=state;LocalDate today=ReminderReceiver.today(getTargetContext());
            String milk=UUID.randomUUID().toString();created.add(milk);state.change(milk,Map.of("name","Молоко · UI","unit","мл","initialQuantity","500","expiry",today.plusDays(10).toString(),"openingDays","5"));
            String[] colours={"Зелёный","Жёлтый","Красный","Чёрный"};int[] offsets={5,1,-1,-3};int[] expected={Ui.GREEN,android.graphics.Color.rgb(232,180,34),Ui.RED,android.graphics.Color.BLACK};String[] colourIds=new String[4];
            for(int n=0;n<4;n++){String id=UUID.randomUUID().toString();colourIds[n]=id;created.add(id);state.change(id,Map.of("name",colours[n]+" · UI","unit","шт","initialQuantity","1","expiry",today.plusDays(offsets[n]).toString()));}
            EditorActivity editor=(EditorActivity)startActivitySync(new Intent(getTargetContext(),EditorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();String name="Кефир · "+UUID.randomUUID().toString().substring(0,8);
            check(ui(()->{Spinner unit=editor.findViewById(R.id.unit_selector);return unit!=null&&unit.getCount()>=9;}),"unit dropdown exists");
            runOnMainSync(()->{((EditText)editor.findViewById(R.id.product_name_input)).setText(name);((EditText)editor.findViewById(R.id.quantity_input)).setText("1,5");select(editor.findViewById(R.id.unit_selector),"л");((Spinner)editor.findViewById(R.id.opening_days_selector)).setSelection(6);((EditText)editor.findViewById(R.id.expiry_input)).setText(today.plusDays(20).toString());views(editor).stream().filter(v->v instanceof Button b&&b.getText().toString().equals("Сохранить упаковку")).findFirst().orElseThrow().performClick();});
            until(()->state.products().stream().anyMatch(p->p.name.equals(name)));Product litre=state.products().stream().filter(p->p.name.equals(name)).findFirst().orElseThrow();created.add(litre.id);
            check(litre.quantityMilli==1500&&litre.unit.equals("л")&&litre.openingDays==7,"form saves decimal litres and selected opening duration");
            MainActivity main=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();until(()->ui(()->main.findViewById(R.id.add_product)!=null));
            check(ui(()->((TextView)main.findViewById(R.id.family_label)).getText().toString().equals("Семья")),"family heading shortened");
            check(ui(()->{View gear=main.findViewById(R.id.toolbar_settings);LinearLayout nav=main.findViewById(R.id.bottom_nav);return gear instanceof ImageButton&&nav.getChildCount()==2&&views(main).stream().noneMatch(v->v instanceof Button b&&b.getText().toString().equals("Настройки"));}),"settings gear replaces bottom navigation settings");
            check(ui(()->main.findViewById(R.id.storage_status)==null),"storage status absent from fridge");
            check(ui(()->{View add=main.findViewById(R.id.add_product),all=main.findViewById(R.id.filter_all);return add.getWidth()>0&&all.getWidth()==add.getWidth()&&all.getTop()>=add.getBottom();}),"All filter spans same width directly below Add");
            check(ui(()->{View all=main.findViewById(R.id.filter_all),soon=main.findViewById(R.id.filter_soon),expired=main.findViewById(R.id.filter_expired),empty=main.findViewById(R.id.filter_empty);View row=(View)soon.getParent();return soon.getParent()==expired.getParent()&&expired.getParent()==empty.getParent()&&row.getWidth()==all.getWidth()&&row.getTop()>=all.getBottom()&&Math.abs(soon.getWidth()-expired.getWidth())<=1&&Math.abs(soon.getWidth()-empty.getWidth())<=1;}),"three equal filters share full-width row below All");
            for(int n=0;n<4;n++){final int index=n;check(ui(()->{View dot=card(main,colourIds[index]).findViewById(R.id.expiry_indicator);return dot.getContentDescription()!=null&&((GradientDrawable)dot.getBackground()).getColor().getDefaultColor()==expected[index];}),"indicator colour matches expiry category "+colours[n]);}
            runOnMainSync(()->((Spinner)card(main,milk).findViewById(R.id.opening_days_selector)).setSelection(6));until(()->product(state,milk).openingDays==7);
            until(()->ui(()->((Spinner)card(main,milk).findViewById(R.id.opening_days_selector)).getSelectedItemPosition()==6));runOnMainSync(()->card(main,milk).findViewById(R.id.card_open).performClick());until(()->product(state,milk).opened!=null);
            check(product(state,milk).expiry.equals(today.plusDays(7)),"Open uses selected duration in one click");
            until(()->ui(()->((Button)card(main,milk).findViewById(R.id.card_open)).getText().toString().equals("Отменить открытие")));runOnMainSync(()->card(main,milk).findViewById(R.id.card_open).performClick());until(()->product(state,milk).opened==null);
            check(product(state,milk).expiry.equals(today.plusDays(10)),"undo opening retains original package expiry");
            runOnMainSync(()->card(main,milk).findViewById(R.id.card_consume).performClick());setProgress(250);confirmConsumption();until(()->product(state,milk).quantityMilli==250000);check(product(state,milk).quantityMilli==250000,"slider consumes 250 ml rather than one unit");
            until(()->ui(()->views(main).stream().anyMatch(v->v instanceof TextView t&&t.getText().toString().equals("250 мл"))));
            runOnMainSync(()->main.findViewById(R.id.toolbar_settings).performClick());until(()->ui(()->main.findViewById(R.id.storage_status)!=null));check(ui(()->((TextView)main.findViewById(R.id.storage_status)).getText().toString().equals("Сохранено на телефоне")),"saved-on-phone status shown in settings");
            runOnMainSync(()->main.findViewById(R.id.toolbar_settings).performClick());until(()->ui(()->main.findViewById(R.id.add_product)!=null));
            runOnMainSync(()->card(main,litre.id).findViewById(R.id.card_consume).performClick());setProgress(1250);confirmConsumption();until(()->product(state,litre.id).quantityMilli==1250);check(product(state,litre.id).quantityMilli==1250,"litres slider preserves 1.25 litre remainder exactly");
            waitForIdleSync();Bitmap screenshot=getUiAutomation().takeScreenshot();if(screenshot!=null){try(OutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"ui-02.png"))){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}screenshot.recycle();}
            runOnMainSync(main::finish);waitForIdleSync();
            for(String id:created)state.change(id,Map.of("deleted","true"));created.clear();
            result.putString("stream","PASS: "+checks+" UI acceptance checks for product dropdowns, sliders, settings, colours and filter layout\n");result.putInt("checks",checks);sendStatus(0,status());finish(Activity.RESULT_OK,result);
        }catch(Throwable e){String message="FAIL after "+checks+" UI checks: "+e+"\n"+android.util.Log.getStackTraceString(e);Bundle failed=status();failed.putString("stack",message);sendStatus(-2,failed);result.putString("stream",message);finish(Activity.RESULT_CANCELED,result);
        }finally{if(store!=null)for(String id:created)try{store.change(id,Map.of("deleted","true"));}catch(Exception ignored){}}
    }
}
