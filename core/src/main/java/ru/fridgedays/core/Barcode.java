package ru.fridgedays.core;

import java.util.regex.*;

/** GTIN identity only: never treat an arbitrary QR URL as a product lookup endpoint. */
public final class Barcode {
    private Barcode(){}
    public static String canonical(String input){
        String value=input==null?"":input.trim();
        if(!value.matches("(?:[0-9]{8}|[0-9]{12,14})"))throw new IllegalArgumentException("Укажите EAN-8, UPC, EAN-13 или GTIN-14 цифрами");
        int sum=0,weight=3;for(int n=value.length()-2;n>=0;n--){sum+=(value.charAt(n)-'0')*weight;weight=4-weight;}
        if((10-sum%10)%10!=value.charAt(value.length()-1)-'0')throw new IllegalArgumentException("Контрольная цифра штрихкода не совпадает. Проверьте код");
        return "0".repeat(14-value.length())+value;
    }
    public static String fromScan(String raw){
        String value=raw==null?"":raw.trim();if(value.matches("[0-9]{8}|[0-9]{12,14}"))return canonical(value);
        if(value.startsWith("]d2")||value.startsWith("]Q3")||value.startsWith("]C1"))value=value.substring(3);
        Matcher gs1=Pattern.compile("^(?:\\(01\\)|01)([0-9]{14})").matcher(value);
        if(gs1.find())return canonical(gs1.group(1));
        Matcher link=Pattern.compile("^https://[^/?#]+/01/([0-9]{14})(?:[/?#].*)?$").matcher(value);
        if(link.matches())return canonical(link.group(1));
        throw new IllegalArgumentException("В этом QR нет кода товара GTIN. Найдите линейный штрихкод на упаковке");
    }
    public static String lookupCode(String canonical){String code=canonical(canonical);return code.startsWith("000000")?code.substring(6):code.startsWith("0")?code.substring(1):code;}
    public static PhotoDraft familyDraft(Product p){
        PhotoDraft d=new PhotoDraft("");d.put("name",p.name,"Семейная история",false);d.put("openingDays",Integer.toString(p.openingDays),"Выбранный семьёй срок после открытия; проверьте новую упаковку",false);
        String unit=p.unit.replace(".","");if(java.util.Set.of("шт","г","кг","мл","л","упак","порц","бут","банка").contains(unit)){
            d.put("quantity",Amounts.format(p.initialQuantityMilli),"Количество при добавлении прежней упаковки, проверьте фактический остаток",false);d.put("unit",unit,"Семейная история",false);
        }
        d.note("Это новая упаковка: срок годности введите вручную или распознайте по фото. Дата прежней упаковки не переносится");return d;
    }
}
