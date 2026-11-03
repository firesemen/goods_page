package ru.fridgedays.core;

import java.math.BigDecimal;
import java.util.Set;

/** Exact thousandths: legacy integer quantities keep their original meaning. */
public final class Amounts {
    public static final long MAX_MILLI=100_000_000L;
    private static final Set<String> COUNTABLE=Set.of("шт","шт.","штук","упак","упак.","порц","порц.","бут","бут.","банка");
    private Amounts(){}
    public static long parse(String value){
        String s=value.trim().replace(',','.');
        if(!s.matches("[+-]?\\d{1,6}(\\.\\d{1,3})?"))throw new IllegalArgumentException("Укажите число, не более 3 знаков после запятой");
        long result=new BigDecimal(s).movePointRight(3).longValueExact();
        if(Math.abs(result)>MAX_MILLI)throw new IllegalArgumentException("Количество не должно превышать 100000");
        return result;
    }
    public static String format(long milli){return BigDecimal.valueOf(milli,3).stripTrailingZeros().toPlainString();}
    public static String display(long milli){return format(milli).replace('.',',');}
    public static boolean countable(String unit){return COUNTABLE.contains(unit);}
    public static long sliderStep(String unit,long available){
        long step=unit.equals("л")||unit.equals("кг")?1:1000;
        return available%step==0?step:1;
    }
}
