package ru.fridgedays.core;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

public final class Product {
    public final String id, name, unit, photo;
    public final int quantity;
    public final LocalDate printedExpiry, opened, expiry;
    public final boolean deleted;
    Product(String id, Map<String,String> f, long delta) {
        this.id=id; name=f.getOrDefault("name", "Без названия"); unit=f.getOrDefault("unit", "шт.");
        quantity=(int)Math.max(0, Math.min(100_000, Long.parseLong(f.getOrDefault("initialQuantity","1"))+delta));
        photo=f.getOrDefault("photo", ""); deleted=Boolean.parseBoolean(f.getOrDefault("deleted","false"));
        printedExpiry=date(f.get("expiry")); opened=date(f.get("opened"));
        LocalDate afterOpening=opened==null?null:opened.plusDays(3);
        expiry=afterOpening==null?printedExpiry:printedExpiry==null?afterOpening:afterOpening.isBefore(printedExpiry)?afterOpening:printedExpiry;
    }
    private static LocalDate date(String s){ return s==null||s.isEmpty()?null:LocalDate.parse(s); }
    public long daysLeft(LocalDate today){ return expiry==null?Long.MAX_VALUE:ChronoUnit.DAYS.between(today,expiry); }
    public String status(LocalDate today) {
        long d=daysLeft(today);
        if(d<0) return d>=-2?"Официальный срок закончился · дополнительные 2 дня":"Просрочено на "+(-d)+" дн.";
        if(d==0)return "Срок заканчивается сегодня";
        if(d==1)return "Срок заканчивается завтра";
        return expiry==null?"Срок не указан":"Осталось "+d+" дн.";
    }
}
