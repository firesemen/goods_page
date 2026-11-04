package ru.fridgedays.core;

import java.time.LocalDate;
import java.util.*;

/** Uncommitted recognition suggestions. Nothing here changes the inventory. */
public final class PhotoDraft {
    public static final Set<String> KEYS=Set.of("name","quantity","unit","manufactured","expiry","shelfDays","openingDays");
    public static final class Value {
        public final String text,evidence;
        public final boolean calculated;
        Value(String text,String evidence,boolean calculated){this.text=text;this.evidence=evidence;this.calculated=calculated;}
    }
    public final Map<String,Value> fields=new LinkedHashMap<>();
    public final List<String> unknownDates=new ArrayList<>(),notes=new ArrayList<>();
    public final String recognizedText;
    public PhotoDraft(String text){recognizedText=text==null?"":text.substring(0,Math.min(16000,text.length()));}
    public PhotoDraft put(String key,String value,String evidence,boolean calculated){
        if(!KEYS.contains(key)||value==null||value.trim().isEmpty())return this;
        value=value.trim();if(value.length()>200)throw new IllegalArgumentException("Слишком длинное распознанное поле");
        switch(key){
            case "quantity"->{long n=Amounts.parse(value);if(n<=0)throw new IllegalArgumentException("Некорректное количество");value=Amounts.format(n);}
            case "unit"->{value=LabelParser.unit(value);if(value==null)throw new IllegalArgumentException("Неизвестная единица");}
            case "manufactured","expiry"->{try{value=LocalDate.parse(value).toString();}catch(java.time.DateTimeException e){throw new IllegalArgumentException("Некорректная дата",e);}}
            case "openingDays"->{int n=Integer.parseInt(value);if(n<1||n>365)throw new IllegalArgumentException("Некорректный срок после открытия");}
            case "shelfDays"->{int n=Integer.parseInt(value);if(n<1||n>100000)throw new IllegalArgumentException("Некорректный срок хранения");}
            default->{}
        }
        String proof=evidence==null?"":evidence.trim();fields.put(key,new Value(value,proof.substring(0,Math.min(400,proof.length())),calculated));return this;
    }
    public PhotoDraft unknownDate(String iso){String date=LocalDate.parse(iso).toString();if(!unknownDates.contains(date)&&unknownDates.size()<8)unknownDates.add(date);return this;}
    public PhotoDraft note(String note){if(note!=null&&!note.isEmpty()&&!notes.contains(note)&&notes.size()<12)notes.add(note.substring(0,Math.min(400,note.length())));return this;}
    public boolean empty(){return fields.isEmpty()&&unknownDates.isEmpty();}
}
