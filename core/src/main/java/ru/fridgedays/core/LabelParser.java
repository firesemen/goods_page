package ru.fridgedays.core;

import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Conservative Russian/English label extraction. Dates without a role stay unassigned. */
public final class LabelParser {
    private static final int FLAGS=Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE;
    private static Pattern pattern(String p){return Pattern.compile(p,FLAGS);}
    private static final Pattern DATE=pattern("(?<![0-9])(?:(20[0-9]{2})[./-]([0-9]{1,2})[./-]([0-9]{1,2})|([0-9]{1,2})[./-]([0-9]{1,2})[./-]([0-9]{4}|[0-9]{2}))(?![0-9])");
    private static final Pattern MADE=pattern("дата\\s*(?:изготовления|производства|выпуска)|изготовлен[ао]?|произведен[ао]?|\\bизг\\.?|manufactured|manufacturing|\\bmfg\\b");
    private static final Pattern EXPIRY=pattern("годен(?:\\s*до)?|годна(?:\\s*до)?|годно(?:\\s*до)?|годны(?:\\s*до)?|срок\\s*годности\\s*(?:до|:)|употребить\\s*до|использовать\\s*до|best\\s*before|use\\s*by|expiry|\\bexp\\b");
    private static final Pattern SHELF=pattern("срок\\s*годности[^\\n0-9]{0,25}([0-9]{1,5})\\s*(?:суток|сутки|сут|дней|дня|день|days?)");
    private static final Pattern OPENING=pattern("(?:после\\s*(?:вскрытия|открытия)|вскрытую\\s*упаковку)[\\s\\S]{0,90}?([0-9]{1,3})\\s*(суток|сутки|сут|дней|дня|день|days?|часов|часа|час|ч\\b|hours?)");
    private static final Pattern AMOUNT=pattern("(?<![0-9.,/])([0-9]{1,6}(?:[.,][0-9]{1,3})?)\\s*(килограмм(?:а|ов)?|миллилитр(?:а|ов)?|литр(?:а|ов)?|кг|мл|гр|шт|kg|ml|pcs|г|л|g|l)(?:\\.)?(?=$|[\\s,;:().])");
    private static final Pattern FOOD=pattern("(?<!\\p{L})(?:молок|кефир|йогурт|сыр|творог|сосиск|колбас|масло|сметан|сливк|яйц|сок|вода|куриц|рыба|салат|хлеб|соус|milk|yogurt|cheese|butter|sausage)[\\p{L}]*");
    private static final Pattern META=pattern("срок|изготов|производ|хран|вскры|открыт|годен|годна|годно|состав|пищевая|энергет|белки|жиры|углевод|калори|адрес|телефон|масса|нетто|объ[её]м|net weight|net volume|\\bооо\\b|\\bгост\\b|\\beac\\b|manufactur|expiry|best before|use by|nutrition|ingredients|storage");
    private LabelParser(){}
    private static int last(Pattern pattern,String text){Matcher m=pattern.matcher(text);int index=-1;while(m.find())index=m.start();return index;}
    public static String unit(String raw){
        String u=raw.trim().toLowerCase(Locale.ROOT).replace(".","");
        if(u.equals("шт")||u.equals("штук")||u.equals("pcs"))return "шт";
        if(u.equals("г")||u.equals("гр")||u.equals("g"))return "г";
        if(u.equals("кг")||u.equals("kg")||u.startsWith("килограмм"))return "кг";
        if(u.equals("мл")||u.equals("ml")||u.startsWith("миллилитр"))return "мл";
        if(u.equals("л")||u.equals("l")||u.startsWith("литр"))return "л";
        return Set.of("упак","порц","бут","банка").contains(u)?u:null;
    }
    private record Fact(String value,String evidence){}
    public static PhotoDraft parse(String input){
        PhotoDraft draft=new PhotoDraft(input);String text=draft.recognizedText.replace('\r','\n').replace('\u00a0',' ');
        Map<String,List<Fact>> dates=new LinkedHashMap<>();dates.put("manufactured",new ArrayList<>());dates.put("expiry",new ArrayList<>());
        Matcher matches=DATE.matcher(text);int previousEnd=0;
        while(matches.find()){
            int contextStart=Math.max(previousEnd,Math.max(0,matches.start()-100));previousEnd=matches.end();
            try{
                boolean iso=matches.group(1)!=null;String year=iso?matches.group(1):matches.group(6);int y=Integer.parseInt(year);if(year.length()==2){y+=2000;draft.note("У даты указан год двумя цифрами — проверьте его");}
                LocalDate date=LocalDate.of(y,Integer.parseInt(iso?matches.group(2):matches.group(5)),Integer.parseInt(iso?matches.group(3):matches.group(4)));
                if(y<2000||y>2100){draft.note("Найдена дата с необычным годом; проверьте исходный снимок");continue;}
                String before=text.substring(contextStart,matches.start());int made=last(MADE,before),expiry=last(EXPIRY,before);String role=made<0&&expiry<0||made>=0&&expiry>=0?null:made>expiry?"manufactured":"expiry";
                if(made>=0&&expiry>=0)draft.note("Перед датой указано несколько назначений — выберите его вручную");
                String proof=(before.substring(Math.max(0,before.lastIndexOf('\n')+1))+matches.group()).trim();
                if(role==null)draft.unknownDate(date.toString());else dates.get(role).add(new Fact(date.toString(),proof));
                if(!iso&&matches.group().contains("/")&&date.getDayOfMonth()<=12&&date.getMonthValue()<=12)draft.note("Дата через / может иметь другой порядок дня и месяца; проверьте формат");
            }catch(DateTimeException|NumberFormatException e){draft.note("В надписи найдена некорректная дата — потребуется проверка");}
        }
        for(var entry:dates.entrySet()){
            Set<String> values=new LinkedHashSet<>();for(Fact f:entry.getValue())values.add(f.value);
            if(values.size()==1){Fact f=entry.getValue().get(0);draft.put(entry.getKey(),f.value,f.evidence,false);}
            else if(values.size()>1){for(String value:values)draft.unknownDate(value);draft.note("Найдено несколько разных дат одного назначения — выберите нужную вручную");}
        }
        Matcher shelf=SHELF.matcher(text);Set<String> shelfValues=new LinkedHashSet<>();String shelfProof="";
        while(shelf.find()){shelfValues.add(shelf.group(1));shelfProof=shelf.group();}
        if(shelfValues.size()==1)try{draft.put("shelfDays",shelfValues.iterator().next(),shelfProof,false);}catch(IllegalArgumentException e){draft.note("Срок хранения вне допустимого диапазона");}
        Matcher opening=OPENING.matcher(text);Set<String> openingValues=new LinkedHashSet<>();String openingProof="";
        while(opening.find()){
            if(SHELF.matcher(opening.group()).find()){draft.note("Назначение срока после открытия неясно — проверьте вручную");continue;}
            if(opening.group(2).toLowerCase(Locale.ROOT).startsWith("ч")||opening.group(2).toLowerCase(Locale.ROOT).startsWith("hour")){draft.note("Срок после открытия указан в часах. В календарные дни он автоматически не переводится");continue;}
            openingValues.add(opening.group(1));openingProof=opening.group();
        }
        if(openingValues.size()==1)try{draft.put("openingDays",openingValues.iterator().next(),openingProof,false);}catch(IllegalArgumentException e){draft.note("Срок после открытия требует ручного ввода");}
        else if(openingValues.size()>1)draft.note("Есть разные сроки после открытия; выберите нужный вручную");
        if(!draft.fields.containsKey("expiry")&&draft.fields.containsKey("manufactured")&&draft.fields.containsKey("shelfDays")){
            LocalDate made=LocalDate.parse(draft.fields.get("manufactured").text);int days=Integer.parseInt(draft.fields.get("shelfDays").text);
            draft.put("expiry",made.plusDays(days).toString(),"Рассчитано: "+made+" + "+days+" дней. Проверьте правило на упаковке",true);
        }
        if(draft.fields.containsKey("expiry")&&draft.fields.containsKey("manufactured")&&LocalDate.parse(draft.fields.get("expiry").text).isBefore(LocalDate.parse(draft.fields.get("manufactured").text)))draft.note("Даты противоречат друг другу: годен до раньше изготовления");
        List<Fact> amounts=new ArrayList<>(),explicit=new ArrayList<>();Matcher amount=AMOUNT.matcher(text);
        while(amount.find()){
            int lineStart=text.lastIndexOf('\n',amount.start())+1,lineEnd=text.indexOf('\n',amount.end());if(lineEnd<0)lineEnd=text.length();String line=text.substring(lineStart,lineEnd),left=text.substring(lineStart,amount.start());
            if(pattern("(?:на|в)\\s*$").matcher(left).find()||pattern("белки|жиры|углевод|энергет|nutrition|calories").matcher(line).find())continue;
            String unit=unit(amount.group(2));if(unit==null)continue;
            if(unit.equals("г")&&(MADE.matcher(line).find()||EXPIRY.matcher(line).find()))continue;
            try{long value=Amounts.parse(amount.group(1));if(value<=0||(Amounts.countable(unit)&&value%1000!=0))continue;Fact f=new Fact(Amounts.format(value)+"|"+unit,amount.group());amounts.add(f);if(pattern("масса|нетто|объ[её]м|net\\s*(?:weight|wt|volume)|количество").matcher(left).find())explicit.add(f);}catch(IllegalArgumentException ignored){}
        }
        List<Fact> pool=explicit.isEmpty()?amounts:explicit;Set<String> unique=new LinkedHashSet<>();for(Fact f:pool)unique.add(f.value);
        if(unique.size()==1){Fact f=pool.get(0);String[] parts=f.value.split("\\|");draft.put("quantity",parts[0],f.evidence,false);draft.put("unit",parts[1],f.evidence,false);}
        else if(unique.size()>1)draft.note("Найдено несколько масс или объёмов — подтвердите количество вручную");
        String best=null;int bestScore=-1,index=0;
        for(String raw:text.split("\n")){
            String line=raw.trim().replaceAll("\\s+"," ");int letters=line.replaceAll("[^\\p{L}]","").length();
            if(line.length()<4||line.length()>160||letters<4||META.matcher(line).find()){index++;continue;}
            int score=(FOOD.matcher(line).find()?100:0)+Math.max(0,20-index);if(score>bestScore){bestScore=score;best=line;}index++;
        }
        if(best!=null)draft.put("name",best,best,false);
        if(draft.empty())draft.note("Подходящие поля не найдены. Снимите надпись ближе и без бликов");
        return draft;
    }
}
