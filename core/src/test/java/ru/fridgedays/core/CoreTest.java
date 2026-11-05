package ru.fridgedays.core;

import java.util.*;
import java.time.*;
import java.io.*;
import java.security.GeneralSecurityException;

public final class CoreTest {
    static int checks;
    static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
    interface Action {void run()throws Exception;}
    static void rejects(Action a,String s)throws Exception{boolean failed=false;try{a.run();}catch(Exception e){failed=true;}check(failed,s);}
    static String uuid(){return UUID.randomUUID().toString();}
    static Product first(Ledger l){return l.products().get(0);}
    public static void main(String[] args)throws Exception{
        String a=uuid(),b=uuid(),p=uuid();Ledger one=new Ledger();
        Operation create=one.change(a,p,Map.of("name","Сосиски","initialQuantity","3","expiry","2026-11-20","unit","шт."));
        one.change(a,p,Map.of("opened","2026-11-03"));
        check(first(one).expiry.equals(LocalDate.parse("2026-11-06")),"3 calendar days, inclusive end of date");
        check(first(one).status(LocalDate.parse("2026-11-07")).startsWith("Официальный"),"grace stays expired day 1");
        check(first(one).status(LocalDate.parse("2026-11-08")).startsWith("Официальный"),"grace day 2");
        check(first(one).status(LocalDate.parse("2026-11-09")).startsWith("Просрочено"),"never auto-delete after grace");
        one.change(a,p,Map.of("opened",""));check(first(one).expiry.equals(first(one).printedExpiry),"undo opening");
        one.change(a,p,Map.of("expiry","2026-11-04","opened","2026-11-03"));check(first(one).expiry.toString().equals("2026-11-04"),"never extend printed expiry");
        one.change(a,p,Map.of("expiry","2026-01-01","opened","2025-12-30"));check(first(one).expiry.toString().equals("2026-01-01"),"year crossing");
        Ledger left=new Ledger(),right=new Ledger();left.merge(List.of(create));right.merge(List.of(create));
        left.change(a,p,Map.of("delta","-1"));right.change(b,p,Map.of("delta","-1"));
        left.change(a,p,Map.of("name","Сосиски домашние"));right.change(b,p,Map.of("opened","2026-11-03"));
        List<Operation> union=new ArrayList<>(left.operations());union.addAll(right.operations());
        left.merge(union);right.merge(union);right.merge(union);
        check(first(left).quantity==1&&first(right).quantity==1,"both offline consumption actions retained, replay idempotent");
        check(first(left).name.equals("Сосиски домашние")&&first(left).opened!=null,"independent field edits survive");
        left.change(a,p,Map.of("deleted","true"));right.change(b,p,Map.of("name","Новая этикетка"));
        left.merge(right.operations());right.merge(left.operations());check(first(right).deleted,"old edit cannot resurrect tombstone");
        for(int i=0;i<100;i++){Collections.shuffle(union,new Random(i));Ledger l=new Ledger();l.merge(union);check(first(l).quantity==1&&first(l).name.equals("Сосиски домашние"),"permutation converges");}
        Operation same1=new Operation(uuid(),a,p,99,Map.of("name","A"));Operation same2=new Operation(uuid(),b,p,99,Map.of("name","B"));
        Ledger l1=new Ledger(),l2=new Ledger();l1.merge(List.of(same1,same2));l2.merge(List.of(same2,same1));check(first(l1).name.equals(first(l2).name),"deterministic tie break");
        for(Operation op:left.operations()){Operation copy=Codec.operation(Codec.operation(op));check(Arrays.equals(Codec.operation(op),Codec.operation(copy)),"binary roundtrip");}
        rejects(()->Codec.operation(new byte[]{0,0,0,1,127,-1,-1,-1}),"hostile length rejected");
        rejects(()->left.merge(List.of(new Operation(create.id,a,p,1,Map.of("name","tampered")))),"id collision rejected");
        rejects(()->new Operation(uuid(),a,p,1,Map.of("expiry","2026-99-99")),"invalid date");
        rejects(()->new Operation(uuid(),a,p,1,Map.of("delta","-2147483648")),"integer overflow");
        Invitation invitation=Invitation.create();Invitation copy=Invitation.parse(invitation.link());check(Arrays.equals(copy.key,invitation.key)&&copy.room().equals(invitation.room()),"invitation roundtrip");
        byte[] data=Codec.operation(create);String sealed=Crypto.seal(invitation.key,invitation.context(create.id),data);
        check(Arrays.equals(data,Crypto.open(copy.key,copy.context(create.id),sealed)),"paired phone decrypts");
        check(!sealed.equals(Crypto.seal(copy.key,copy.context(create.id),data)),"random nonce");
        rejects(()->Crypto.open(Crypto.newKey(),copy.context(create.id),sealed),"wrong key");
        rejects(()->Crypto.open(copy.key,copy.context(uuid()),sealed),"bound to operation id");
        byte[] damaged=Crypto.decode(sealed);damaged[damaged.length-1]^=1;
        rejects(()->Crypto.open(copy.key,copy.context(create.id),Crypto.encode(damaged)),"tamper rejected");
        Map<String,String> packets=Map.of(create.id,sealed);check(Codec.packets(Codec.packets(packets)).equals(packets),"packet archive roundtrip");
        Backup snapshot=new Backup(invitation,packets);Backup restored=Backup.parse(snapshot.bytes());check(restored.packets.equals(packets)&&restored.invitation.room().equals(invitation.room()),"full backup codec includes family and photos");
        byte[] broken=snapshot.bytes();broken[3]=99;rejects(()->Backup.parse(broken),"unknown backup version rejected");
        String backup=Crypto.passwordSeal("a sufficiently long password".toCharArray(),data);
        check(Arrays.equals(data,Crypto.passwordOpen("a sufficiently long password".toCharArray(),backup)),"password backup");
        rejects(()->Crypto.passwordOpen("wrong".toCharArray(),backup),"wrong backup password");
        rejects(()->Invitation.parse("https://example.com#abc"),"foreign invite rejected");
        rejects(()->new Invitation(uuid(),uuid(),"X","http://example.com",Crypto.newKey()),"relay HTTPS required");
        rejects(()->new Invitation(uuid(),uuid(),"X","https://user:password@example.com",Crypto.newKey()),"no relay URL credentials");
        check(!Crypto.capability(copy.key,copy.room()).equals(Crypto.encode(copy.key)),"relay capability separated from encryption key");
        Ledger leap=new Ledger();leap.change(a,uuid(),Map.of("name","Кефир","expiry","2028-03-20","opened","2028-02-27"));check(first(leap).expiry.toString().equals("2028-03-01"),"calendar days across leap day");
        Ledger exhausted=new Ledger();exhausted.merge(List.of(create));exhausted.change(a,p,Map.of("delta","-3"));exhausted.change(b,p,Map.of("delta","-1"));check(first(exhausted).quantity==0,"concurrent consumption never shows negative stock");
        check(Crypto.sha256("abc").equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),"relay registration SHA256 agrees with standard vector");
        check(first(exhausted).openingDays==3&&Amounts.parse(create.fields.get("initialQuantity"))==3000,"legacy packets retain 3 days and their original unit quantities");
        Ledger customDays=new Ledger();String custom=uuid();customDays.change(a,custom,Map.of("name","Молоко","initialQuantity","1.5","unit","л","expiry","2026-12-20","opened","2026-11-04","openingDays","7"));
        check(first(customDays).expiry.toString().equals("2026-11-11"),"custom 7 calendar days after opening");
        customDays.change(a,custom,Map.of("openingDays","1"));check(first(customDays).expiry.toString().equals("2026-11-05"),"changing opening duration recalculates from original opening date");
        customDays.change(a,custom,Map.of("openingDays","30","expiry","2026-11-06"));check(first(customDays).expiry.toString().equals("2026-11-06"),"custom duration never extends package expiry");
        customDays.change(a,custom,Map.of("opened",""));check(first(customDays).expiry.equals(first(customDays).printedExpiry),"undo custom opening restores printed expiry");
        Ledger fractionalA=new Ledger(),fractionalB=new Ledger();Operation litre=fractionalA.change(a,uuid(),Map.of("name","Молоко","unit","л","initialQuantity","0.3","expiry","2026-12-20"));fractionalB.merge(List.of(litre));
        fractionalA.change(a,litre.product,Map.of("delta","-0.1"));fractionalB.change(b,litre.product,Map.of("delta","-0.1"));fractionalA.merge(fractionalB.operations());fractionalB.merge(fractionalA.operations());
        check(first(fractionalA).quantityMilli==100&&first(fractionalB).quantityMilli==100,"fractional offline consumption has no floating point drift");
        check(Amounts.parse("1,25")==1250&&Amounts.display(1250).equals("1,25")&&Amounts.format(1250).equals("1.25"),"Russian decimal input and canonical wire format");
        check(Amounts.sliderStep("мл",500000)==1000&&Amounts.sliderStep("л",1500)==1&&Amounts.sliderStep("шт",3000)==1000,"slider uses whole ml/items and thousandths of litres");
        rejects(()->Amounts.parse("0.0001"),"precision limit");rejects(()->Amounts.parse("1e3"),"scientific notation rejected");rejects(()->Amounts.parse("100001"),"quantity bounds");
        rejects(()->new Operation(uuid(),a,p,1,Map.of("openingDays","0")),"opening days zero rejected");rejects(()->new Operation(uuid(),a,p,1,Map.of("openingDays","366")),"opening days overflow rejected");
        Ledger colours=new Ledger();String cp=uuid();LocalDate date=LocalDate.of(2026,11,4);colours.change(a,cp,Map.of("name","Тест","expiry",date.plusDays(2).toString()));
        check(first(colours).expiryState(date)==Product.ExpiryState.GREEN,"green at 2 days remaining");
        colours.change(a,cp,Map.of("expiry",date.plusDays(1).toString()));check(first(colours).expiryState(date)==Product.ExpiryState.YELLOW,"yellow tomorrow");
        colours.change(a,cp,Map.of("expiry",date.toString()));check(first(colours).expiryState(date)==Product.ExpiryState.YELLOW,"yellow today");
        colours.change(a,cp,Map.of("expiry",date.minusDays(1).toString()));check(first(colours).expiryState(date)==Product.ExpiryState.RED,"red at 1 expired day");
        colours.change(a,cp,Map.of("expiry",date.minusDays(2).toString()));check(first(colours).expiryState(date)==Product.ExpiryState.RED,"red at exactly 2 expired days");
        colours.change(a,cp,Map.of("expiry",date.minusDays(3).toString()));check(first(colours).expiryState(date)==Product.ExpiryState.BLACK,"black beyond 2 expired days");
        System.out.println("PASS: "+checks+" checks (dates, convergence, duplicate delivery, deletion, codecs, encryption, backups)");
        PhotoDraftTest.main(args);
    }
}
