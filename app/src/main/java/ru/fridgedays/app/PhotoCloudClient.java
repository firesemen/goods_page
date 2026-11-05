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
    interface Connections {HttpURLConnection open(URL url)throws IOException;}
    private final Connections connections;
    private volatile boolean cancelled;private volatile HttpURLConnection active;
    PhotoCloudClient(){this(url->(HttpURLConnection)url.openConnection());}
    PhotoCloudClient(Connections connections){this.connections=connections;}
    static String endpoint(Context c){return c.getSharedPreferences("photo-recognition",0).getString("url","");}
    static String origin(String value)throws Exception{
        value=value.trim();if(value.isEmpty())return "";URI u=new URI(value);
        if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getPort()!=-1||u.getQuery()!=null||u.getFragment()!=null||!(u.getPath().isEmpty()||u.getPath().equals("/")))throw new IllegalArgumentException("Укажите HTTPS-адрес Worker без пути, порта и параметров");
        return "https://"+u.getHost().toLowerCase(Locale.ROOT);
    }
    void cancel(){cancelled=true;HttpURLConnection c=active;if(c!=null)c.disconnect();}
    private void check(){if(cancelled||Thread.currentThread().isInterrupted())throw new CancellationException();}
    PhotoDraft recognize(String origin,Invitation family,List<File> images)throws Exception{
        check();if(images.isEmpty()||images.size()>2)throw new IOException("Нужны один или два снимка");
        JSONArray encoded=new JSONArray();for(File image:images){check();encoded.put(PhotoImages.cloud(image));}
        byte[] body=new JSONObject().put("images",encoded).toString().getBytes(StandardCharsets.UTF_8);check();if(body.length>3_000_000)throw new IOException("Снимки слишком большие");
        return parse(request(origin,family,"recognize",body));
    }
    String connectionStatus(String origin,Invitation family)throws Exception{
        JSONObject status=request(origin,family,"status",null);
        if(!"fridge-photo".equals(status.optString("service"))||status.optInt("version")<2||!status.optBoolean("ready"))throw new IOException("Обновите код Worker по инструкции в настройках распознавания");
        int limit=status.getInt("dailyLimit"),remaining=status.getInt("remaining");
        if(limit<1||limit>100||remaining<0||remaining>limit)throw new IOException("Worker вернул некорректный статус");
        return "Worker и доступ семьи проверены. Осталось попыток за сутки UTC: "+remaining+" из "+limit+". Для проверки самой модели распознайте фото упаковки";
    }
    private JSONObject request(String endpoint,Invitation family,String action,byte[] body)throws Exception{
        check();String base=origin(endpoint);if(base.isEmpty())throw new IllegalArgumentException("Сначала укажите HTTPS-адрес Worker");
        HttpURLConnection c=connections.open(new URL(base+"/v1/rooms/"+family.room()+"/"+action));active=c;
        try{check();c.setConnectTimeout(15_000);c.setReadTimeout(body==null?15_000:90_000);c.setInstanceFollowRedirects(false);c.setRequestMethod(body==null?"GET":"POST");
            c.setRequestProperty("Authorization","Bearer "+Crypto.photoCapability(family.key,family.room()));
            c.setRequestProperty("Accept","application/json");
            if(body!=null){c.setDoOutput(true);c.setFixedLengthStreamingMode(body.length);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){check();out.write(body);}}
            check();int status=c.getResponseCode();ByteArrayOutputStream response=new ByteArrayOutputStream();
            InputStream stream=status==200?c.getInputStream():c.getErrorStream();if(stream!=null)try(InputStream in=stream){byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){check();if(response.size()+n>64000)throw new IOException("Слишком большой ответ распознавания");response.write(buffer,0,n);}}
            check();JSONObject json;try{json=new JSONObject(new String(response.toByteArray(),StandardCharsets.UTF_8));}catch(JSONException e){if(status!=200)throw error(status,"");throw new IOException("Worker вернул непонятный ответ. Проверьте его адрес и код",e);}
            if(status!=200)throw error(status,json.optString("error"));return json;
        }catch(SocketTimeoutException e){throw new IOException("Cloudflare не успел ответить. Автоматического повтора нет; попробуйте позже или заполните вручную",e);
        }catch(javax.net.ssl.SSLException e){throw new IOException("Не удалось проверить защищённое соединение с Worker. Проверьте адрес и дату на телефоне",e);
        }catch(UnknownHostException|ConnectException e){throw new IOException("Не удалось подключиться к Worker. Проверьте адрес, интернет и доступность Cloudflare в вашей сети",e);
        }finally{active=null;c.disconnect();}
    }
    private static IOException error(int status,String code){
        String message=switch(status){
            case 401->"Worker не разрешил доступ. Замените ROOMS значением из настроек распознавания этой семьи";
            case 429->"Дневной лимит распознавания исчерпан. Попробуйте позже или заполните карточку вручную";
            case 413->"Снимки слишком большие";
            case 404,405->"Адрес или версия Worker не подходит. Проверьте адрес и обновите код по инструкции распознавания";
            case 503->code.equals("database_not_ready")?"Создайте таблицу recognition_usage в D1 по инструкции и проверьте привязку DB":"Worker не настроен: проверьте привязки AI, DB и переменную ROOMS";
            default->code.equals("invalid_model_response")?"Модель не смогла разобрать маркировку. Снимите текст ближе и без бликов; можно заполнить вручную":code.equals("contradictory_dates")?"Модель вернула противоречивые даты. Проверьте маркировку вручную":"Распознавание недоступно (HTTP "+status+"). Проверьте квоту Workers AI и настройки Worker";
        };return new IOException(message);
    }
    static PhotoDraft parse(JSONObject json)throws Exception{
        try{
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
        }catch(JSONException|IllegalArgumentException|java.time.DateTimeException e){throw new IOException("Модель вернула некорректные поля. Проверьте маркировку вручную",e);}
    }
}
