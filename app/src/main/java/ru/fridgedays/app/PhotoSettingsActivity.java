package ru.fridgedays.app;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import ru.fridgedays.core.*;

public final class PhotoSettingsActivity extends Activity {
    private final java.util.concurrent.ExecutorService network=java.util.concurrent.Executors.newSingleThreadExecutor();
    private PhotoCloudClient checkClient;private Button checkButton;private TextView connectionStatus;private int generation;private boolean checking,destroyed;
    @Override public void onCreate(Bundle state){super.onCreate(state);
        LinearLayout root=Ui.column(this);Ui.inset(this,root);ScrollView scroll=new ScrollView(this);root.addView(scroll);LinearLayout body=Ui.column(this);body.setPadding(24,16,24,32);scroll.addView(body);setContentView(root);
        body.addView(Ui.button(this,"‹ Назад",this::finish));body.addView(Ui.title(this,"Распознавание по фото"));
        body.addView(Ui.text(this,"Cloudflare Workers AI",21,Ui.INK));
        body.addView(Ui.text(this,"Для заполнения карточки нужен интернет и ваш Worker в Cloudflare. Выберите фото упаковки и при необходимости отдельный снимок срока. Отправка начинается только после вашего подтверждения; даты всегда нужно сверить с маркировкой.",14,Ui.MUTED));
        body.addView(Ui.text(this,"Модель получает выбранные снимки в читаемом виде по HTTPS. Сам Worker их не сохраняет. Ключ шифрования списка продуктов модели не передаётся.",14,Ui.MUTED));
        EditText url=Ui.field(this,"HTTPS-адрес Worker распознавания",PhotoCloudClient.endpoint(this),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI,body);url.setId(R.id.ocr_endpoint);
        if(state!=null)url.setText(state.getString("url",url.getText().toString()));
        body.addView(Ui.button(this,"Сохранить адрес распознавания",()->{try{cancelCheck();String address=saveAddress(url);connectionStatus.setText(address.isEmpty()?"Распознавание отключено. Карточки можно заполнять вручную":"Адрес сохранён. Проверьте подключение");Toast.makeText(this,"Адрес сохранён",Toast.LENGTH_SHORT).show();}catch(Exception e){Ui.error(this,e);}}));
        checkButton=Ui.button(this,"Проверить подключение",()->{try{
            String address=saveAddress(url);if(address.isEmpty())throw new IllegalArgumentException("Сначала укажите HTTPS-адрес Worker");
            Invitation family=Store.get(this).invitation();cancelCheck();int request=++generation;PhotoCloudClient client=new PhotoCloudClient();checkClient=client;checking=true;checkButton.setEnabled(false);connectionStatus.setText("Проверяем адрес и доступ семьи…");
            network.execute(()->{try{String message=client.connectionStatus(address,family);runOnUiThread(()->finishCheck(request,message));}catch(Exception e){runOnUiThread(()->finishCheck(request,e.getMessage()==null?"Не удалось проверить подключение":e.getMessage()));}});
        }catch(Exception e){Ui.error(this,e);}});checkButton.setId(R.id.ocr_connection_check);body.addView(checkButton);
        connectionStatus=Ui.text(this,PhotoCloudClient.endpoint(this).isEmpty()?"Worker ещё не подключён":"Адрес сохранён. Проверьте подключение",14,Ui.MUTED);connectionStatus.setId(R.id.ocr_connection_status);body.addView(connectionStatus);
        body.addView(Ui.text(this,"Проверка подключения не отправляет фотографии и не вызывает модель. После неё проверьте распознавание на одной упаковке.",12,Ui.MUTED));
        Ui.space(this,body,16);body.addView(Ui.text(this,"Первая настройка",21,Ui.INK));
        body.addView(Ui.text(this,"1. Откройте инструкцию и создайте Worker с базой D1.\n2. Добавьте параметры ROOMS кнопкой ниже.\n3. Вставьте адрес Worker на обоих телефонах и проверьте подключение.",14,Ui.MUTED));
        body.addView(Ui.button(this,"Параметры ROOMS для распознавания",()->{try{
            Invitation i=Store.get(this).invitation();String json="{\""+i.room()+"\":\""+Crypto.sha256(Crypto.photoCapability(i.key,i.room()))+"\"}";
            TextView text=Ui.text(this,json,13,Ui.INK);text.setTextIsSelectable(true);text.setPadding(24,16,24,16);
            new AlertDialog.Builder(this).setTitle("ROOMS · только распознавание").setView(text).setPositiveButton("Копировать",(d,w)->getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("ROOMS",json))).setNegativeButton("Закрыть",null).show();
        }catch(Exception e){Ui.error(this,e);}}));
        body.addView(Ui.button(this,"Инструкция Cloudflare",()->startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/firesemen/goods_page/blob/fridge-days/recognizer/README.md")))));
        body.addView(Ui.text(this,"Очистите адрес, чтобы отключить распознавание. Удаление адреса не меняет список продуктов и домашний обмен.",12,Ui.MUTED));
    }
    private String saveAddress(EditText url)throws Exception{String address=PhotoCloudClient.origin(url.getText().toString());if(!getSharedPreferences("photo-recognition",0).edit().putString("url",address).commit())throw new java.io.IOException("Не удалось сохранить адрес");url.setText(address);return address;}
    private void finishCheck(int request,String message){if(destroyed||generation!=request)return;checkClient=null;checking=false;checkButton.setEnabled(true);connectionStatus.setText(message);}
    private void cancelCheck(){++generation;if(checkClient!=null)checkClient.cancel();checkClient=null;if(checking&&connectionStatus!=null)connectionStatus.setText("Проверка остановлена");checking=false;if(checkButton!=null)checkButton.setEnabled(true);}
    @Override protected void onStop(){if(checking)cancelCheck();super.onStop();}
    @Override protected void onDestroy(){destroyed=true;cancelCheck();network.shutdown();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);EditText url=findViewById(R.id.ocr_endpoint);if(url!=null)state.putString("url",url.getText().toString());}
}
