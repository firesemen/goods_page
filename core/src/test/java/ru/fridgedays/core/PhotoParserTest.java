package ru.fridgedays.core;

import java.util.*;

public final class PhotoParserTest {
    private static int checks;
    private static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    private static String value(PhotoDraft draft,String key){return draft.fields.containsKey(key)?draft.fields.get(key).text:null;}
    private static void absent(PhotoDraft draft,String key,String message){check(!draft.fields.containsKey(key),message);}
    public static void main(String[] args){
        PhotoDraft milk=LabelParser.parse("МОЛОКО ПАСТЕРИЗОВАННОЕ\nМасса нетто 500 мл\nДата изготовления: 04.11.2026\nГоден до: 14.11.2026\nПосле вскрытия хранить не более 3 суток");
        check(value(milk,"name").equals("МОЛОКО ПАСТЕРИЗОВАННОЕ"),"Russian product name");
        check(value(milk,"quantity").equals("500")&&value(milk,"unit").equals("мл"),"coupled quantity and unit");
        check(value(milk,"manufactured").equals("2026-11-04"),"manufacturing date has its own role");
        check(value(milk,"expiry").equals("2026-11-14"),"explicit expiry date");
        check(value(milk,"openingDays").equals("3"),"opening duration from explicit label");
        check(!milk.fields.get("expiry").calculated,"printed expiry is not marked calculated");
        PhotoDraft sameLine=LabelParser.parse("Изготовлено 04.11.2026; годен до 12.11.2026");check(value(sameLine,"manufactured").equals("2026-11-04")&&value(sameLine,"expiry").equals("2026-11-12"),"two dates on same line classified independently");
        PhotoDraft onlyDate=LabelParser.parse("14.11.2026");absent(onlyDate,"expiry","bare date is never silently assumed to be expiry");check(onlyDate.unknownDates.equals(List.of("2026-11-14")),"bare date requires explicit role selection");
        PhotoDraft following=LabelParser.parse("Изготовлено 04.11.2026\n14.11.2026");check(value(following,"manufactured").equals("2026-11-04")&&following.unknownDates.equals(List.of("2026-11-14")),"date role is not reused for an adjacent unlabelled date");
        PhotoDraft ambiguous=LabelParser.parse("Дата изготовления / годен до\n04.11.2026 / 14.11.2026");check(ambiguous.unknownDates.size()==2&&!ambiguous.fields.containsKey("expiry"),"two role headings cannot silently assign two dates");
        PhotoDraft invalid=LabelParser.parse("Годен до 31.02.2026");absent(invalid,"expiry","invalid calendar date rejected");check(!invalid.notes.isEmpty(),"invalid date explains need for manual check");
        PhotoDraft two=LabelParser.parse("Годен до 10.11.2026\nГоден до 12.11.2026");absent(two,"expiry","conflicting expiry candidates do not pick one");check(two.unknownDates.size()==2,"conflicting dates offered for manual choice");
        PhotoDraft year=LabelParser.parse("Годен до 14.11.26");check(value(year,"expiry").equals("2026-11-14")&&!year.notes.isEmpty(),"two-digit year explicitly flagged");
        PhotoDraft slash=LabelParser.parse("EXP 04/11/2026");check(!slash.notes.isEmpty(),"ambiguous day/month format flagged");
        PhotoDraft hours=LabelParser.parse("После вскрытия хранить 48 часов");absent(hours,"openingDays","hours do not silently become calendar days");check(!hours.notes.isEmpty(),"hours limitation visible");
        PhotoDraft calculated=LabelParser.parse("МОЛОКО\nИзготовлено 04.11.2026\nСрок годности: 30 дней");check(value(calculated,"expiry").equals("2026-12-04")&&calculated.fields.get("expiry").calculated,"manufactured plus shelf life has explicit calculated provenance");
        PhotoDraft actual=LabelParser.parse("Изготовлено 04.11.2026\nСрок годности: 30 дней\nГоден до 10.11.2026");check(value(actual,"expiry").equals("2026-11-10")&&!actual.fields.get("expiry").calculated,"printed date wins over calculation");
        PhotoDraft conflict=LabelParser.parse("Изготовлено 14.11.2026\nГоден до 10.11.2026");check(conflict.notes.stream().anyMatch(s->s.contains("противоречат")),"reversed dates flagged");
        PhotoDraft net=LabelParser.parse("СЫР\nМасса нетто 250 г\nНа 100 г\nБелки 20 г");check(value(net,"quantity").equals("250")&&value(net,"unit").equals("г"),"nutrition quantities do not replace net amount");
        PhotoDraft litres=LabelParser.parse("МОЛОКО\nОбъём 1,5 л");check(value(litres,"quantity").equals("1.5")&&value(litres,"unit").equals("л"),"decimal litres normalized exactly");
        PhotoDraft amounts=LabelParser.parse("500 мл\n250 мл");absent(amounts,"quantity","multiple unlabelled quantities require manual choice");
        PhotoDraft english=LabelParser.parse("MILK\nNet volume 500 ml\nMFG 2026-11-04\nBest before 2026-11-14");check(value(english,"unit").equals("мл")&&value(english,"expiry").equals("2026-11-14"),"English and ISO label");
        PhotoDraft bad=LabelParser.parse("\n\n");check(bad.empty(),"empty photo text does not invent fields");
        PhotoDraft truncated=LabelParser.parse("x".repeat(20000));check(truncated.recognizedText.length()==16000,"bounded recognition text");
        check(LabelParser.unit("гр.").equals("г")&&LabelParser.unit("kg").equals("кг"),"common unit aliases");
        boolean rejected=false;try{new PhotoDraft("").put("quantity","-1","",false);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"negative AI quantity rejected");
        rejected=false;try{new PhotoDraft("").put("expiry","2026-13-40","",false);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"invalid AI date rejected");
        try{byte[] key=Crypto.newKey();String room=UUID.randomUUID()+"_"+UUID.randomUUID();String token=Crypto.photoCapability(key,room);check(token.length()==43&&!token.equals(Crypto.capability(key,room)),"photo and inventory capabilities are separated");check(!token.equals(Crypto.photoCapability(key,UUID.randomUUID()+"_"+UUID.randomUUID())),"photo capability is scoped to a family/fridge");}catch(Exception e){throw new AssertionError(e);}
        System.out.println("PASS: "+checks+" recognition-parser checks (roles, ambiguity, dates, quantities, validation)");
    }
}
