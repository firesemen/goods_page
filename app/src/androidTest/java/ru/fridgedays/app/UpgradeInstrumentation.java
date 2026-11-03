package ru.fridgedays.app;

import android.app.*;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import java.io.*;
import java.util.*;
import ru.fridgedays.core.*;

/** Run seed with the published 0.1 target, then verify after installing 0.2 with -r. */
public final class UpgradeInstrumentation extends Instrumentation {
    private String phase;
    private int checks;
    @Override public void onCreate(Bundle b){super.onCreate(b);phase=b==null?"verify":b.getString("phase","verify");start();}
    private Bundle status(){Bundle b=new Bundle();b.putString("id","InstrumentationTestRunner");b.putInt("numtests",1);b.putInt("current",1);b.putString("class",getClass().getName());b.putString("test",phase.equals("seed")?"seedLegacyProduct":"preserveLegacyDataOnUpgrade");return b;}
    private void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    @Override public void onStart(){sendStatus(1,status());Bundle result=new Bundle();try{
        Store store=Store.get(getTargetContext());File fixture=new File(getTargetContext().getFilesDir(),"upgrade-fixture.bin");
        if(phase.equals("seed")){
            String id=UUID.randomUUID().toString();store.change(id,Map.of("name","Молоко","initialQuantity","500","unit","мл","expiry","2030-12-20"));
            try(var out=new DataOutputStream(new FileOutputStream(fixture))){out.writeUTF(id);out.writeUTF(Crypto.sha256(store.invitation().link()));byte[] packets=Codec.packets(store.packets());out.writeInt(packets.length);out.write(packets);}
            check(fixture.length()>0,"old version persisted the upgrade fixture");
        }else{
            String id,hash;Map<String,String> packets;
            try(var in=new DataInputStream(new FileInputStream(fixture))){id=in.readUTF();hash=in.readUTF();int n=in.readInt();if(n<0||n>Codec.MAX_BUNDLE)throw new IOException("Invalid fixture");byte[] bytes=new byte[n];in.readFully(bytes);packets=Codec.packets(bytes);}
            check(Crypto.sha256(store.invitation().link()).equals(hash),"same family and encryption key after APK update");
            check(store.packets().equals(packets),"encrypted history preserved byte for byte");
            Object product=store.products().stream().filter(p->p.id.equals(id)).findFirst().orElseThrow();
            check(product.getClass().getField("quantityMilli").getLong(product)==500000,"legacy 500 ml still means 500 ml");
            check(product.getClass().getField("openingDays").getInt(product)==3,"legacy opening duration defaults to 3 days");
            MainActivity main=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long limit=System.nanoTime()+20_000_000_000L;boolean[] ready={false};do{runOnMainSync(()->ready[0]=main.findViewById(R.id.filter_all)!=null);if(!ready[0])Thread.sleep(100);}while(!ready[0]&&System.nanoTime()<limit);waitForIdleSync();
            Bitmap screenshot=getUiAutomation().takeScreenshot();if(screenshot!=null){try(OutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"preview-0.2.png"))){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}screenshot.recycle();}
            runOnMainSync(main::finish);fixture.delete();
        }
        result.putString("stream","PASS: "+checks+" upgrade checks ("+phase+")\n");result.putInt("checks",checks);sendStatus(0,status());finish(Activity.RESULT_OK,result);
    }catch(Throwable e){String message="FAIL: "+e+"\n"+android.util.Log.getStackTraceString(e);Bundle failed=status();failed.putString("stack",message);sendStatus(-2,failed);result.putString("stream",message);finish(Activity.RESULT_CANCELED,result);}}
}
