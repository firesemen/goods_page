package ru.fridgedays.core;

import java.io.*;
import java.net.URI;
import java.util.*;

public final class Invitation {
    public final String family,fridge,name,relay;
    public final byte[] key;
    public Invitation(String family,String fridge,String name,String relay,byte[] key){
        UUID.fromString(family);UUID.fromString(fridge);
        if(key.length!=32||name.trim().isEmpty()||name.length()>80)throw new IllegalArgumentException("Некорректное приглашение");
        if(!relay.isEmpty()) {
            URI u=URI.create(relay);
            if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||u.getPort()!=-1||!Set.of("","/").contains(u.getPath()))throw new IllegalArgumentException("Нужен HTTPS-адрес ретранслятора без пути");
        }
        this.family=family;this.fridge=fridge;this.name=name;this.relay=relay.replaceAll("/$","");this.key=key.clone();
    }
    public static Invitation create(){return new Invitation(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Наш холодильник","",Crypto.newKey());}
    public String room(){return family+"_"+fridge;}
    public String context(String id){return "fridgedays-v1:"+room()+":"+id;}
    public String link(){
        try{var b=new ByteArrayOutputStream();var o=new DataOutputStream(b);o.writeInt(1);Codec.string(o,family);Codec.string(o,fridge);Codec.string(o,name);Codec.string(o,relay);o.write(key);
            return "fridgedays://join#"+Crypto.encode(b.toByteArray());}catch(IOException e){throw new UncheckedIOException(e);}
    }
    public static Invitation parse(String link)throws IOException{
        try{if(!link.startsWith("fridgedays://join#")||link.length()>2000)throw new IOException("Это не приглашение Fridge Days");
            var in=new DataInputStream(new ByteArrayInputStream(Crypto.decode(link.substring(18))));if(in.readInt()!=1)throw new IOException("Неизвестная версия приглашения");
            String family=Codec.string(in,36),fridge=Codec.string(in,36),name=Codec.string(in,320),relay=Codec.string(in,500);byte[] key=new byte[32];in.readFully(key);
            if(in.available()!=0)throw new IOException("Лишние данные");return new Invitation(family,fridge,name,relay,key);
        }catch(IllegalArgumentException e){throw new IOException("Некорректное приглашение",e);}
    }
}
