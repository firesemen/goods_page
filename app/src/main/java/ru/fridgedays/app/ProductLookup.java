package ru.fridgedays.app;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;
import ru.fridgedays.core.*;

/** Read-only Open Food Facts request. Never send family IDs, secrets, photos or dates. */
final class ProductLookup {
    interface Connections{HttpURLConnection open(URL url)throws IOException;}
    private final Connections connections;private volatile boolean cancelled;private volatile HttpURLConnection active;
    ProductLookup(){this(url->(HttpURLConnection)url.openConnection());}ProductLookup(Connections c){connections=c;}
    void cancel(){cancelled=true;if(active!=null)active.disconnect();}
    private void check(){if(cancelled)throw new java.util.concurrent.CancellationException();}
    PhotoDraft find(String code)throws Exception{
        check();String gtin=Barcode.canonical(code);String fields="code,product_name_ru,product_name,brands,quantity,product_quantity,product_quantity_unit";
        HttpURLConnection c=connections.open(new URL("https://world.openfoodfacts.org/api/v2/product/"+Barcode.lookupCode(gtin)+".json?fields="+fields));active=c;
        try{check();c.setConnectTimeout(12_000);c.setReadTimeout(15_000);c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent","FridgeDays/0.5.0 (Android; https://github.com/firesemen/goods_page/tree/fridge-days)");c.setRequestProperty("Accept","application/json");
            int status=c.getResponseCode();if(status==404)return null;if(status!=200)throw new IOException(status==429?"Лимит базы товаров. Попробуйте позже":"База товаров недоступна (HTTP "+status+")");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){check();if(bytes.size()+n>150000)throw new IOException("Слишком большой ответ базы товаров");bytes.write(b,0,n);}}
            check();return parse(new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8)),gtin);
        }catch(SocketTimeoutException|UnknownHostException|ConnectException e){throw new IOException("Нет связи с базой товаров. Можно заполнить карточку вручную или по фото",e);}finally{active=null;c.disconnect();}
    }
    private static String text(JSONObject object,String key){Object value=object.opt(key);return value instanceof String?((String)value).trim():"";}
    static PhotoDraft parse(JSONObject response,String expected)throws Exception{
        if(response.optInt("status")!=1)return null;JSONObject p=response.getJSONObject("product");String code=response.optString("code",p.optString("code",""));
        if(!Barcode.canonical(code).equals(Barcode.canonical(expected)))throw new IOException("База вернула другой штрихкод");
        PhotoDraft draft=new PhotoDraft("");String name=text(p,"product_name_ru");if(name.isEmpty())name=text(p,"product_name");String brand=text(p,"brands");
        if(!brand.isEmpty()&&!name.toLowerCase(java.util.Locale.ROOT).contains(brand.toLowerCase(java.util.Locale.ROOT)))name=name.isEmpty()?brand:name+" · "+brand;
        if(!name.isEmpty())draft.put("name",name.substring(0,Math.min(200,name.length())),"Open Food Facts · данные сообщества",false);
        Matcher q=Pattern.compile("^\\s*([0-9]{1,6}(?:[.,][0-9]{1,3})?)\\s*(мл|ml|кг|kg|г|g|л|l|шт|pcs)\\s*$",Pattern.CASE_INSENSITIVE).matcher(p.optString("quantity",""));
        if(q.matches()){
            String unit=switch(q.group(2).toLowerCase(java.util.Locale.ROOT)){case "ml","мл"->"мл";case "kg","кг"->"кг";case "g","г"->"г";case "l","л"->"л";default->"шт";};
            try{long amount=Amounts.parse(q.group(1));if(amount>0&&(!Amounts.countable(unit)||amount%1000==0)){draft.put("quantity",Amounts.format(amount),"Полная упаковка: "+q.group(),false);draft.put("unit",unit,"Open Food Facts",false);}}catch(IllegalArgumentException ignored){}
        }
        draft.note("Open Food Facts · ODbL. Проверьте название и количество по упаковке. Срок годности задайте вручную или по фото");return draft.empty()?null:draft;
    }
}
