package ru.fridgedays.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded binary protocol: no Java object serialization or unbounded allocations. */
public final class Codec {
    public static final int MAX_PACKET=400_000, MAX_BUNDLE=48_000_000;
    public static void string(DataOutputStream out,String s)throws IOException{byte[] b=s.getBytes(StandardCharsets.UTF_8);out.writeInt(b.length);out.write(b);}
    public static String string(DataInputStream in,int max)throws IOException{
        int size=in.readInt();if(size<0||size>max||size>in.available())throw new IOException("Некорректный размер");
        byte[] bytes=new byte[size];in.readFully(bytes);return new String(bytes,StandardCharsets.UTF_8);
    }
    public static byte[] operation(Operation op){
        try{var buf=new ByteArrayOutputStream();var out=new DataOutputStream(buf);out.writeInt(1);
            string(out,op.id);string(out,op.actor);string(out,op.product);out.writeLong(op.clock);out.writeInt(op.fields.size());
            for(var e:op.fields.entrySet()){string(out,e.getKey());string(out,e.getValue());}return buf.toByteArray();
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    public static Operation operation(byte[] bytes)throws IOException{
        if(bytes.length>MAX_PACKET)throw new IOException("Слишком большой пакет");
        try{var in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=1)throw new IOException("Неизвестная версия");
            String id=string(in,36),actor=string(in,36),product=string(in,36);long clock=in.readLong();int count=in.readInt();
            if(count<1||count>10)throw new IOException("Некорректные поля");Map<String,String> f=new TreeMap<>();
            for(int n=0;n<count;n++){String key=string(in,30);if(f.put(key,string(in,240_000))!=null)throw new IOException("Повторное поле");}
            if(in.available()!=0)throw new IOException("Лишние данные");return new Operation(id,actor,product,clock,f);
        }catch(IllegalArgumentException e){throw new IOException("Некорректная операция",e);}
    }
    public static byte[] packets(Map<String,String> packets){
        try{var buf=new ByteArrayOutputStream();var out=new DataOutputStream(buf);out.writeInt(1);out.writeInt(packets.size());
            for(var e:new TreeMap<>(packets).entrySet()){string(out,e.getKey());string(out,e.getValue());}
            byte[] bytes=buf.toByteArray();if(bytes.length>MAX_BUNDLE)throw new IllegalArgumentException("Слишком большой архив");return bytes;
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    public static Map<String,String> packets(byte[] bytes)throws IOException{
        if(bytes.length>MAX_BUNDLE)throw new IOException("Слишком большой архив");var in=new DataInputStream(new ByteArrayInputStream(bytes));
        if(in.readInt()!=1)throw new IOException("Неизвестная версия");int n=in.readInt();if(n<0||n>Ledger.MAX_OPERATIONS)throw new IOException("Слишком много пакетов");
        Map<String,String> result=new TreeMap<>();for(int i=0;i<n;i++){String id=string(in,36);try{UUID.fromString(id);}catch(IllegalArgumentException e){throw new IOException(e);}
            if(result.put(id,string(in,MAX_PACKET))!=null)throw new IOException("Повторный ID");}
        if(in.available()!=0)throw new IOException("Лишние данные");return result;
    }
}
