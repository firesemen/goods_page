package ru.fridgedays.app;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import ru.fridgedays.core.*;

public final class PhotoSettingsActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);
        LinearLayout root=Ui.column(this);Ui.inset(this,root);ScrollView scroll=new ScrollView(this);root.addView(scroll);LinearLayout body=Ui.column(this);body.setPadding(24,16,24,32);scroll.addView(body);setContentView(root);
        body.addView(Ui.button(this,"‹ Назад",this::finish));body.addView(Ui.title(this,"Распознавание по фото"));
        body.addView(Ui.text(this,"На телефоне · без интернета",21,Ui.INK));
        body.addView(Ui.text(this,"Русский и английский словари уже в приложении. Распознавание запускается только по кнопке; фото не отправляются автоматически.",14,Ui.MUTED));
        Ui.space(this,body,16);body.addView(Ui.text(this,"Облако · по отдельному запросу",21,Ui.INK));
        body.addView(Ui.text(this,"Для сложной упаковки можно отправить выбранные снимки вашему Worker с Workers AI. Модель увидит надписи и изображения; передача по HTTPS. Сам Worker фото и текст не сохраняет. Локальные данные семьи остаются зашифрованы, а распознавание в облаке требует доступа модели к снимкам.",14,Ui.MUTED));
        EditText url=Ui.field(this,"HTTPS-адрес Worker распознавания",PhotoCloudClient.endpoint(this),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI,body);url.setId(R.id.ocr_endpoint);
        if(state!=null)url.setText(state.getString("url",url.getText().toString()));
        body.addView(Ui.button(this,"Сохранить адрес распознавания",()->{try{String origin=PhotoCloudClient.origin(url.getText().toString());getSharedPreferences("photo-recognition",0).edit().putString("url",origin).apply();url.setText(origin);Toast.makeText(this,origin.isEmpty()?"Облако распознавания отключено":"Адрес сохранён",Toast.LENGTH_SHORT).show();}catch(Exception e){Ui.error(this,e);}}));
        body.addView(Ui.button(this,"Параметры ROOMS для распознавания",()->{try{
            Invitation i=Store.get(this).invitation();String json="{\""+i.room()+"\":\""+Crypto.sha256(Crypto.photoCapability(i.key,i.room()))+"\"}";
            TextView text=Ui.text(this,json,13,Ui.INK);text.setTextIsSelectable(true);text.setPadding(24,16,24,16);
            new AlertDialog.Builder(this).setTitle("ROOMS · только распознавание").setView(text).setPositiveButton("Копировать",(d,w)->getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("ROOMS",json))).setNegativeButton("Закрыть",null).show();
        }catch(Exception e){Ui.error(this,e);}}));
        body.addView(Ui.text(this,"Установите отдельный Worker по инструкции из GitHub. Его адрес укажите на обоих телефонах. Поле можно очистить, чтобы отключить облачное распознавание.",14,Ui.MUTED));
        body.addView(Ui.button(this,"Инструкция Cloudflare",()->startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/firesemen/goods_page/blob/fridge-days/recognizer/README.md")))));
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);EditText url=findViewById(R.id.ocr_endpoint);if(url!=null)state.putString("url",url.getText().toString());}
}
