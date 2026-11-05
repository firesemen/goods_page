package ru.fridgedays.app;

import android.app.*;
import android.os.Bundle;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import ru.fridgedays.core.*;

/** Two independent journals using the real Android NSD and both production socket peers. */
public final class WifiInstrumentation extends Instrumentation {
    private int checks;
    private Bundle status(){Bundle b=new Bundle();b.putString("id","InstrumentationTestRunner");b.putInt("numtests",1);b.putInt("current",1);b.putString("class",getClass().getName());b.putString("test","twoIndependentWifiReplicas");return b;}
    @Override public void onCreate(Bundle b){super.onCreate(b);start();}
    private void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private void progress(String message){Bundle b=new Bundle();b.putString("stream",message+"\n");sendStatus(2,b);}
    private void until(BooleanSupplier condition,String message)throws Exception{long limit=System.nanoTime()+90_000_000_000L;while(!condition.getAsBoolean()){if(System.nanoTime()>limit)throw new AssertionError(message);Thread.sleep(100);}}
    private static Product product(Replica r,String id){return r.products().stream().filter(p->p.id.equals(id)).findFirst().orElseThrow();}
    private static final class Replica implements SyncReplica {
        private final Invitation invitation;
        private final String actor=UUID.randomUUID().toString();
        private final Ledger ledger=new Ledger();
        private final Map<String,String> packets=new TreeMap<>();
        Replica(Invitation invitation){this.invitation=invitation;}
        public String actorId(){return actor;}
        public Invitation invitation(){return invitation;}
        public synchronized Map<String,String> packets(){return new TreeMap<>(packets);}
        public synchronized void merge(Map<String,String> incoming)throws Exception{
            List<Operation> decoded=new ArrayList<>();for(var e:incoming.entrySet()){
                Operation op=Codec.operation(Crypto.open(invitation.key,invitation.context(e.getKey()),e.getValue()));
                if(!op.id.equals(e.getKey()))throw new IllegalArgumentException("Operation ID mismatch");decoded.add(op);
            }
            ledger.merge(decoded);incoming.forEach(packets::putIfAbsent);
        }
        synchronized void change(String product,Map<String,String> fields)throws Exception{
            Operation op=ledger.change(actor,product,fields);packets.put(op.id,Crypto.seal(invitation.key,invitation.context(op.id),Codec.operation(op)));
        }
        synchronized List<Product> products(){return ledger.products();}
    }
    @Override public void onStart(){sendStatus(1,status());Bundle result=new Bundle();NearbySync left=null,right=null,outsider=null;
        try{
            Invitation invitation=Invitation.create();Replica a=new Replica(invitation),b=new Replica(Invitation.parse(invitation.link())),other=new Replica(Invitation.create());
            List<String> leftStatus=new CopyOnWriteArrayList<>(),rightStatus=new CopyOnWriteArrayList<>();
            String sausages=UUID.randomUUID().toString(),yogurt=UUID.randomUUID().toString();
            a.change(sausages,Map.of("barcode",Barcode.canonical("4601234567893"),"name","Сосиски","initialQuantity","3","unit","шт.","expiry",LocalDate.now().plusDays(10).toString()));
            b.change(yogurt,Map.of("name","Йогурт","initialQuantity","1.5","unit","л","expiry",LocalDate.now().plusDays(15).toString()));
            // More than 20 events and more than one photo-size transfer batch, in both directions.
            String photo=java.util.Base64.getEncoder().encodeToString(new byte[170_000]);
            for(int n=0;n<26;n++)a.change(sausages,Map.of("unit",n%2==0?"шт.":"штук"));
            for(int n=0;n<4;n++)b.change(yogurt,Map.of("photo",photo));
            left=new NearbySync(getTargetContext(),a,leftStatus::add);right=new NearbySync(getTargetContext(),b,rightStatus::add);outsider=new NearbySync(getTargetContext(),other,s->{});
            progress("Waiting for Android NSD discovery and initial bidirectional exchange");
            until(()->a.packets().size()==32&&b.packets().size()==32,"NSD did not discover and synchronize both replicas");
            check(a.packets().equals(b.packets()),"both independent journals converge over real sockets");
            check(product(b,sausages).barcode.equals(Barcode.canonical("4601234567893")),"barcode metadata reaches the other family replica");
            check(product(b,sausages).quantity==3&&product(a,yogurt).photo.equals(photo),"product and photo bytes delivered in both directions");
            until(()->!leftStatus.isEmpty()&&!rightStatus.isEmpty(),"Missing peer exchange completion status");
            check(leftStatus.stream().anyMatch(s->s.startsWith("Синхронизировано"))&&rightStatus.stream().anyMatch(s->s.startsWith("Синхронизировано")),"successful exchange reported by both participants");
            check(other.packets().isEmpty(),"uninvited family receives no operations");

            left.close();left=null;right.close();right=null;
            a.change(sausages,Map.of("delta","-1"));b.change(sausages,Map.of("delta","-1"));a.change(sausages,Map.of("opened",LocalDate.now().toString(),"openingDays","7"));
            b.change(yogurt,Map.of("deleted","true"));a.change(yogurt,Map.of("name","Йогурт новая этикетка"));
            a.change(yogurt,Map.of("delta","-0.25"));b.change(yogurt,Map.of("delta","-0.125"));
            check(product(a,sausages).quantity==2&&product(b,sausages).quantity==2,"offline operations remain separate before reconnection");
            left=new NearbySync(getTargetContext(),a,leftStatus::add);right=new NearbySync(getTargetContext(),b,rightStatus::add);
            progress("Waiting for reconnection and merge of offline changes");
            until(()->a.packets().size()==39&&b.packets().size()==39,"Reconnection failed to exchange offline changes");
            check(product(a,sausages).quantity==1&&product(b,sausages).quantity==1,"two offline consumption actions retain the correct remaining quantity");
            check(product(a,sausages).expiry.equals(LocalDate.now().plusDays(7))&&product(b,sausages).openingDays==7,"custom opening date and duration synchronized");
            check(product(a,yogurt).quantityMilli==1125&&product(b,yogurt).quantityMilli==1125,"fractional quantities synchronize exactly across offline peers");
            check(product(a,yogurt).deleted&&product(b,yogurt).deleted,"concurrent rename does not resurrect deleted packaging");
            check(a.packets().equals(b.packets()),"both ciphertext journals match after reconnect");

            int count=a.packets().size();
            // Repeat discovery with the same operation IDs; the journal and quantity must be stable.
            left.close();left=null;right.close();right=null;leftStatus.clear();rightStatus.clear();
            left=new NearbySync(getTargetContext(),a,leftStatus::add);right=new NearbySync(getTargetContext(),b,rightStatus::add);
            progress("Waiting for duplicate-delivery exchange");
            until(()->leftStatus.stream().anyMatch(s->s.startsWith("Синхронизировано"))&&rightStatus.stream().anyMatch(s->s.startsWith("Синхронизировано")),"Repeat exchange failed");
            check(a.packets().size()==count&&b.packets().size()==count&&product(a,sausages).quantity==1&&product(b,sausages).quantity==1,"repeated exchanges are idempotent");
            check(other.packets().isEmpty(),"family isolation holds across reconnection");
            result.putString("stream","PASS: "+checks+" Wi-Fi checks with 2 independent journals, Android NSD and production sockets; 39 unique events\n");result.putInt("checks",checks);sendStatus(0,status());finish(Activity.RESULT_OK,result);
        }catch(Throwable e){String message="FAIL after "+checks+" Wi-Fi checks: "+e+"\n"+android.util.Log.getStackTraceString(e);Bundle failed=status();failed.putString("stack",message);sendStatus(-2,failed);result.putString("stream",message);finish(Activity.RESULT_CANCELED,result);
        }finally{if(left!=null)left.close();if(right!=null)right.close();if(outsider!=null)outsider.close();}
    }
}
