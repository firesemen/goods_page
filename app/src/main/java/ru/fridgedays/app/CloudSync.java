package ru.fridgedays.app;

import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import ru.fridgedays.core.*;

public final class CloudSync {
    private static final Object LOCK=new Object();
    public static String run(Store store)throws Exception{
        synchronized(LOCK){
            if(!store.beginCloud())return "Синхронизация уже выполняется";
            try{
            Invitation i=store.invitation();if(i.relay.isEmpty())return "Облако не подключено · обмен рядом доступен";
            String token=Crypto.capability(i.key,i.room()),root=i.relay+"/v1/rooms/"+i.room();
            request("PUT",root,token,"{}");
            for(int n=0;n<1000;n++){
                Map<String,String> pending=store.pending();if(pending.isEmpty())break;
                JSONArray events=new JSONArray();for(var e:pending.entrySet())events.put(new JSONObject().put("id",e.getKey()).put("payload",e.getValue()));
                request("POST",root+"/events",token,new JSONObject().put("events",events).toString());store.acknowledge(pending.keySet());
            }
            for(int n=0;n<1000;n++){
                JSONObject page=new JSONObject(request("GET",root+"/events?after="+store.cursor(),token,null));
                JSONArray events=page.getJSONArray("events");Map<String,String> incoming=new LinkedHashMap<>();
                for(int a=0;a<events.length();a++){JSONObject event=events.getJSONObject(a);incoming.put(event.getString("id"),event.getString("payload"));}
                store.merge(incoming);store.acknowledge(incoming.keySet());store.cursor(page.getLong("cursor"));
                if(!page.getBoolean("hasMore"))return "Синхронизировано с облаком";
            }
            throw new IOException("История слишком большая для одного обмена; повторите синхронизацию");
            }finally{store.endCloud();}
        }
    }
    private static String request(String method,String url,String token,String body)throws Exception{
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Синхронизация отменена");
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(15_000);c.setReadTimeout(30_000);c.setRequestMethod(method);
        c.setRequestProperty("Authorization","Bearer "+token);c.setRequestProperty("Content-Type","application/json");
        try{
            if(body!=null){c.setDoOutput(true);try(OutputStream out=c.getOutputStream()){out.write(body.getBytes(StandardCharsets.UTF_8));}}
            int code=c.getResponseCode();if(code<200||code>=300)throw new IOException("Ретранслятор вернул HTTP "+code);
            try(InputStream in=c.getInputStream()){return new String(read(in,12_000_000),StandardCharsets.UTF_8);}
        }finally{c.disconnect();}
    }
    public static byte[] read(InputStream in,int limit)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
        while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("Превышен размер данных");out.write(b,0,n);}return out.toByteArray();
    }
}
