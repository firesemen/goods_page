package ru.fridgedays.core;
import java.util.*;

/** Cloud suggestions must obey inventory field bounds and use a separate authorization. */
public final class PhotoDraftTest {
    private static int checks;
    private static void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    private static void reject(String field,String value){boolean failed=false;try{new PhotoDraft("").put(field,value,"label",false);}catch(IllegalArgumentException e){failed=true;}check(failed,"invalid suggestion accepted: "+field+"="+value);}
    public static void main(String[] args)throws Exception{
        PhotoDraft draft=new PhotoDraft("Молоко 1,5 л").put("quantity","1,5","1,5 л",false).put("unit","л","1,5 л",false).put("expiry","2026-11-14","Годен до 14.11.2026",false);
        check(draft.fields.get("quantity").text.equals("1.5"),"exact decimal amount normalization");
        check(draft.fields.get("expiry").evidence.equals("Годен до 14.11.2026"),"date retains printed evidence");
        for(String n:List.of("-1","0","100000.001","1.2345"))reject("quantity",n);
        reject("unit","ложки");reject("expiry","2026-02-31");reject("manufactured","2026-13-01");reject("openingDays","0");reject("openingDays","366");reject("shelfDays","100001");
        draft.unknownDate("2026-11-10").unknownDate("2026-11-10");check(draft.unknownDates.size()==1&&!draft.fields.get("expiry").text.equals("2026-11-10"),"unassigned date never replaces expiry");
        PhotoDraft longText=new PhotoDraft("x".repeat(20000));check(longText.recognizedText.length()==16000,"bounded model transcription");
        byte[] key=Crypto.newKey();String room=UUID.randomUUID()+"_"+UUID.randomUUID();String token=Crypto.photoCapability(key,room);
        check(token.length()==43&&!token.equals(Crypto.capability(key,room)),"recognition authorization cannot authorize stock relay");
        check(!token.equals(Crypto.photoCapability(key,UUID.randomUUID()+"_"+UUID.randomUUID())),"recognition authorization is scoped to a family/fridge");
        System.out.println("PASS: "+checks+" cloud draft checks (dates, amounts, evidence, separate authorization)");
    }
}
