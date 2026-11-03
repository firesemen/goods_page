package ru.fridgedays.core;

import java.io.*;
import java.util.*;

public final class Backup {
    public final Invitation invitation;public final Map<String,String> packets;
    public Backup(Invitation invitation,Map<String,String> packets){this.invitation=invitation;this.packets=Collections.unmodifiableMap(new TreeMap<>(packets));}
    public byte[] bytes()throws IOException{
        var b=new ByteArrayOutputStream();var out=new DataOutputStream(b);out.writeInt(1);Codec.string(out,invitation.link());byte[] payload=Codec.packets(packets);out.writeInt(payload.length);out.write(payload);return b.toByteArray();
    }
    public static Backup parse(byte[] bytes)throws IOException{
        if(bytes.length>Codec.MAX_BUNDLE+4000)throw new IOException("Слишком большая копия");var in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=1)throw new IOException("Неизвестная копия");Invitation invite=Invitation.parse(Codec.string(in,2000));int size=in.readInt();if(size<0||size>Codec.MAX_BUNDLE||size!=in.available())throw new IOException("Некорректный размер копии");byte[] data=new byte[size];in.readFully(data);return new Backup(invite,Codec.packets(data));
    }
}
