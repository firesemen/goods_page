package ru.fridgedays.core;

import java.util.*;

public final class Ledger {
    public static final int MAX_OPERATIONS=20_000;
    private final Map<String,Operation> operations=new TreeMap<>();
    private long clock;
    public synchronized void merge(Collection<Operation> incoming) {
        Map<String,Operation> additions=new HashMap<>();
        for(Operation op:incoming){
            Operation existing=operations.get(op.id);
            if(existing==null)existing=additions.get(op.id);
            if(existing!=null && !Arrays.equals(Codec.operation(existing),Codec.operation(op)))throw new IllegalArgumentException("Одинаковый ID с разными данными");
            if(existing==null)additions.put(op.id,op);
        }
        if(operations.size()+additions.size()>MAX_OPERATIONS)throw new IllegalArgumentException("Достигнут лимит истории; сохраните резервную копию");
        operations.putAll(additions);
        for(Operation op:additions.values())clock=Math.max(clock,op.clock);
    }
    public synchronized Operation change(String actor,String product,Map<String,String> fields){
        Operation op=new Operation(UUID.randomUUID().toString(),actor,product,clock+1,fields);
        merge(List.of(op)); return op;
    }
    public synchronized List<Operation> operations(){return new ArrayList<>(operations.values());}
    public synchronized int size(){return operations.size();}
    public synchronized List<Product> products() {
        Map<String,Map<String,Operation>> winners=new HashMap<>();
        Map<String,Long> deltas=new HashMap<>();
        for(Operation op:operations.values()){
            Map<String,Operation> fields=winners.computeIfAbsent(op.product, k->new HashMap<>());
            for(String k:op.fields.keySet()) {
                if(k.equals("delta")){ deltas.merge(op.product,Amounts.parse(op.fields.get(k)),Long::sum); continue; }
                Operation old=fields.get(k);
                if(old==null||Operation.compare(op,old)>0)fields.put(k,op);
            }
        }
        List<Product> result=new ArrayList<>();
        for(var e:winners.entrySet()){
            if(!e.getValue().containsKey("name"))continue;
            Map<String,String> f=new HashMap<>(); e.getValue().forEach((k,v)->f.put(k,v.fields.get(k)));
            result.add(new Product(e.getKey(),f,deltas.getOrDefault(e.getKey(),0L)));
        }
        result.sort(Comparator.comparing((Product p)->p.expiry,Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(p->p.id));
        return result;
    }
}
