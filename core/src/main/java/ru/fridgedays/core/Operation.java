package ru.fridgedays.core;

import java.time.LocalDate;
import java.util.*;

/** Immutable operation. Only quantity deltas commute; scalar fields use Lamport LWW. */
public final class Operation {
    public final String id, actor, product;
    public final long clock;
    public final Map<String, String> fields;
    private static final Set<String> ALLOWED = Set.of("name", "initialQuantity", "unit", "expiry", "manufactured", "shelfDays", "opened", "photo", "deleted", "delta");
    public Operation(String id, String actor, String product, long clock, Map<String, String> fields) {
        UUID.fromString(id); UUID.fromString(actor); UUID.fromString(product);
        if (clock < 1 || clock > 9_000_000_000_000_000L || fields.isEmpty() || fields.size() > 10) throw new IllegalArgumentException("Некорректная операция");
        for (var entry : fields.entrySet()) {
            String k = entry.getKey(), v = entry.getValue();
            if (!ALLOWED.contains(k) || v == null || v.length() > (k.equals("photo") ? 240_000 : 200)) throw new IllegalArgumentException("Некорректное поле");
            switch (k) {
                case "expiry", "manufactured", "opened" -> { if (!v.isEmpty()) LocalDate.parse(v); }
                case "initialQuantity", "shelfDays" -> { int n = Integer.parseInt(v); if(n < 1 || n > 100_000) throw new IllegalArgumentException("Некорректное количество"); }
                case "delta" -> { int n = Integer.parseInt(v); if (n == 0 || Math.abs((long)n) > 100_000) throw new IllegalArgumentException("Некорректное изменение количества"); }
                case "deleted" -> { if (!v.equals("true") && !v.equals("false")) throw new IllegalArgumentException("Некорректное удаление"); }
                case "name", "unit" -> { if(v.trim().isEmpty()) throw new IllegalArgumentException("Пустое название или единица"); }
                default -> {}
            }
        }
        if (fields.containsKey("delta") && fields.size() != 1) throw new IllegalArgumentException("Количество изменяется отдельной операцией");
        this.id=id; this.actor=actor; this.product=product; this.clock=clock; this.fields=Collections.unmodifiableMap(new TreeMap<>(fields));
    }
    public static int compare(Operation a, Operation b) {
        int c=Long.compare(a.clock,b.clock);
        if(c==0)c=a.actor.compareTo(b.actor);
        return c==0?a.id.compareTo(b.id):c;
    }
}
