package ru.fridgedays.app;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import ru.fridgedays.core.*;

final class PhotoCloudClient {
    private volatile boolean cancelled;private volatile HttpURLConnection active;
    static String endpoint(Context c){return c.getSharedPreferences("photo-recognition",0).getString("url","");}
    static String origin(String value)throws Exception{
        value=value.trim();if(value.isEmpty())return "";URI u=new URI(value);
        if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getPort()!=-1||u.getQuery()!=null||u.getFragment()!=null||!(u.getPath().isEmpty()||u.getPath().equals("/")))throw new IllegalArgumentException("Укажите HTTPS-адрес Worker без пути, порта и параметров");
        return "https://"+u.getHost().toLowerCase(Locale.ROOT);
    }
    void cancel(){cancelled=true;HttpURLConnection c=active;if(c!=null)c.disconnect();}
    private void check(){if(cancelled||Thread.currentThread().isInterrupted())throw new CancellationException();}
    PhotoDraft recognize(String origin,Invitation family,List<File> images)throws Exception{
        check();JSONArray encoded=new JSONArray();for(File image:images){check();encoded.put(PhotoImages.cloud(image));}
        byte[] body=new JSONObject().put("images",encoded).toString().getBytes(StandardCharsets.UTF_8);check();if(body.length>3_000_000)throw new IOException("Снимки слишком большие");
        HttpURLConnection c=(HttpURLConnection)new URL(origin(origin)+"/v1/rooms/"+family.room()+"/recognize").openConnection();active=c;
        try{check();c.setConnectTimeout(15_000);c.setReadTimeout(90_000);c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setDoOutput(true);c.setFixedLengthStreamingMode(body.length);
            c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Authorization","Bearer "+Crypto.photoCapability(family.key,family.room()));
            try(OutputStream out=c.getOutputStream()){out.write(body);}check();int status=c.getResponseCode();
            if(status!=200)throw new IOException(switch(status){case 401->"Worker не разрешил доступ. Проверьте ROOMS из настроек распознавания";case 429->"Дневной лимит облачного распознавания исчерпан. Используйте распознавание на телефоне";case 413->"Снимки слишком большие";case 503->"Worker не настроен: проверьте привязки AI, DB и ROOMS";default->"Облако не ответило корректно (HTTP "+status+"). Можно распознать на телефоне";});
            ByteArrayOutputStream response=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){check();if(response.size()+n>64000)throw new IOException("Слишком большой ответ распознавания");response.write(buffer,0,n);}}
            check();return parse(new JSONObject(new String(response.toByteArray(),StandardCharsets.UTF_8)));
        }catch(java.net.SocketTimeoutException e){throw new IOException("Облако не успело ответить. Автоматического повтора нет; можно распознать на телефоне",e);}finally{active=null;c.disconnect();}
    }
    static PhotoDraft parse(JSONObject json)throws Exception{
        PhotoDraft draft=new PhotoDraft(json.optString("recognizedText",""));JSONObject fields=json.getJSONObject("fields"),proof=json.getJSONObject("evidence");
        Iterator<String> keys=fields.keys();while(keys.hasNext()){
            String key=keys.next();if(!PhotoDraft.KEYS.contains(key)||!(fields.get(key) instanceof String))throw new IOException("Облако вернуло некорректные поля");
            if(!(proof.opt(key) instanceof String)||proof.getString(key).trim().isEmpty())throw new IOException("Нет подтверждения распознанного поля");
            draft.put(key,fields.getString(key),proof.getString(key),false);
        }
        JSONArray unknown=json.optJSONArray("unknownDates"),notes=json.optJSONArray("notes");
        if(unknown!=null)for(int n=0;n<Math.min(8,unknown.length());n++)draft.unknownDate(unknown.getString(n));
        if(notes!=null)for(int n=0;n<Math.min(12,notes.length());n++)draft.note(notes.getString(n));
        if(draft.fields.containsKey("manufactured")&&draft.fields.containsKey("expiry")&&draft.fields.get("expiry").text.compareTo(draft.fields.get("manufactured").text)<0)throw new IOException("Даты противоречат друг другу. Проверьте маркировку вручную");
        if(draft.fields.containsKey("quantity")!=draft.fields.containsKey("unit"))throw new IOException("Нет пары количества и единицы");
        return draft;
    }
}
