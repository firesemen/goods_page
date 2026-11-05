package ru.fridgedays.core;
import java.util.*;
public final class BarcodeTest {
    static int checks;static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        String ean="4601234567893",gtin=Barcode.canonical(ean);check(gtin.equals("04601234567893"),"EAN normalization");check(Barcode.canonical("036000291452").equals(Barcode.canonical("0036000291452")),"UPC and EAN alias");check(Barcode.lookupCode(Barcode.canonical("96385074")).equals("96385074"),"EAN8 lookup");
        check(Barcode.fromScan("]d201"+gtin+"21serial\u001d91private").equals(gtin),"Data Matrix extracts only GTIN");check(Barcode.fromScan("https://id.gs1.org/01/"+gtin+"/21/serial").equals(gtin),"GS1 digital link");
        for(String bad:List.of("4601234567890","https://example.com/product","123","4601234567893 OR 1=1")){boolean rejected=false;try{Barcode.fromScan(bad);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"invalid/unknown QR rejected");}
        Ledger l=new Ledger();String actor=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();l.change(actor,id,Map.of("name","Молоко","initialQuantity","500","unit","мл","barcode",gtin,"expiry","2026-01-01","openingDays","7"));l.change(actor,id,Map.of("delta","-250"));l.change(actor,id,Map.of("deleted","true"));
        Product p=l.products().get(0);PhotoDraft draft=Barcode.familyDraft(p);check(p.barcode.equals(gtin)&&p.quantityMilli==250000,"barcode survives spending and deletion");check(draft.fields.get("quantity").text.equals("500"),"reuse full original pack, not remaining quantity");check(!draft.fields.containsKey("expiry")&&!draft.fields.containsKey("manufactured")&&!draft.fields.containsKey("shelfDays"),"never reuse dates");
        byte[] key=Crypto.newKey();Ledger replica=new Ledger();for(Operation op:l.operations())replica.merge(List.of(Codec.operation(Crypto.open(key,"test",Crypto.seal(key,"test",Codec.operation(op))))));check(replica.products().get(0).barcode.equals(gtin),"barcode in encrypted journal roundtrip");
        System.out.println("PASS: "+checks+" barcode checks (GTIN, QR, history, amounts, encrypted merge)");
    }
}
