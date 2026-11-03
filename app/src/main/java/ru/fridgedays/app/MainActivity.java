package ru.fridgedays.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import java.time.*;
import java.util.*;
import java.io.*;
import java.util.concurrent.*;
import ru.fridgedays.core.*;

public final class MainActivity extends Activity {
    private Store store;private LinearLayout page,body;private TextView statusView;
    private NearbySync nearby;private String status="Хранится на этом телефоне",tab="fridge",filter="Все";
    private final ExecutorService io=Executors.newSingleThreadExecutor();private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean cloudRequested=new java.util.concurrent.atomic.AtomicBoolean();
    private boolean visible;private char[] backupPassword;private boolean pendingImport;
    private final Runnable refreshCloud=new Runnable(){public void run(){if(visible&&store!=null&&!store.invitation().relay.isEmpty())syncCloud();if(visible)handler.postDelayed(this,60_000);}};
    @Override public void onCreate(Bundle state){super.onCreate(state);LinearLayout loading=Ui.column(this);loading.setPadding(32,64,32,32);loading.addView(Ui.title(this,"Сроки"));loading.addView(Ui.text(this,"Открываем холодильник…",16,Ui.MUTED));setContentView(loading);
        if(state!=null){tab=state.getString("tab","fridge");filter=state.getString("filter","Все");}
        io.execute(()->{try{store=Store.get(this);ReminderReceiver.schedule(this);SyncJob.schedule(this,store);try{ReminderReceiver.sendIfDue(this,store);}catch(Exception ignored){}runOnUiThread(()->{render();if(visible){startNearby();refreshCloud.run();}handleLink(getIntent());});}catch(Exception e){runOnUiThread(()->Ui.error(this,e));}});
    }
    @Override protected void onStart(){super.onStart();visible=true;if(store!=null){render();startNearby();handler.removeCallbacks(refreshCloud);refreshCloud.run();}}
    @Override protected void onStop(){visible=false;handler.removeCallbacks(refreshCloud);stopNearby();super.onStop();}
    @Override protected void onDestroy(){io.shutdown();network.shutdownNow();if(backupPassword!=null)Arrays.fill(backupPassword,'\0');super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putString("tab",tab);b.putString("filter",filter);}
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);handleLink(i);}
    private void handleLink(Intent i){if(i!=null&&i.getData()!=null){String link=i.getData().toString();i.setData(null);new AlertDialog.Builder(this).setTitle("Присоединиться к холодильнику?").setMessage("Принимайте приглашение только от члена семьи.").setPositiveButton("Присоединиться",(d,w)->join(link)).setNegativeButton("Отмена",null).show();}}
    private void render(){
        if(store==null||isFinishing())return;
        LinearLayout root=Ui.column(this);Ui.inset(this,root);root.setBackgroundColor(Ui.BG);setContentView(root);
        ScrollView scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));page=Ui.column(this);page.setPadding(Ui.dp(this,20),Ui.dp(this,20),Ui.dp(this,20),Ui.dp(this,20));scroll.addView(page);
        page.addView(Ui.text(this,"СРОКИ  /  НАША СЕМЬЯ",12,Ui.GREEN));
        page.addView(Ui.title(this,tab.equals("family")?"Вместе проще":tab.equals("settings")?"Настройки":store.invitation().name));
        statusView=Ui.text(this,status,12,Ui.MUTED);page.addView(statusView);Ui.space(this,page,12);
        body=Ui.column(this);page.addView(body);
        if(tab.equals("fridge"))fridge();else if(tab.equals("family"))family();else settings();
        LinearLayout nav=Ui.row(this);nav.setPadding(Ui.dp(this,8),0,Ui.dp(this,8),0);
        String[][] tabs={{"fridge","Холодильник"},{"family","Семья"},{"settings","Настройки"}};
        for(String[] item:tabs){Button b=Ui.button(this,item[1],()->{tab=item[0];render();});b.setTypeface(null,tab.equals(item[0])?Typeface.BOLD:Typeface.NORMAL);nav.addView(b,new LinearLayout.LayoutParams(0,-2,1));}root.addView(nav);
    }
    private void fridge(){
        LocalDate today=ReminderReceiver.today(this);List<Product> all=store.products();long active=all.stream().filter(p->!p.deleted&&p.quantity>0).count();
        LinearLayout banner=Ui.column(this);Ui.card(this,banner);banner.setBackground(Ui.background(Color.rgb(225,237,224),18,this));banner.addView(Ui.text(this,"Сначала используйте то, что заканчивается",18,Ui.GREEN));banner.addView(Ui.text(this,active+" упаковок · "+today,13,Ui.MUTED));body.addView(banner);Ui.space(this,body,10);
        body.addView(Ui.button(this,"＋ Добавить продукт",()->startActivityForResult(new Intent(this,EditorActivity.class),30)));
        HorizontalScrollView filters=new HorizontalScrollView(this);filters.setHorizontalScrollBarEnabled(false);LinearLayout row=Ui.row(this);filters.addView(row);
        for(String f:new String[]{"Все","Скоро","Просрочено","Закончились"}){Button b=Ui.button(this,f,()->{filter=f;render();});b.setTypeface(null,filter.equals(f)?Typeface.BOLD:Typeface.NORMAL);row.addView(b);}body.addView(filters);
        int shown=0;for(Product p:all){if(p.deleted)continue;if(filter.equals("Закончились")?p.quantity>0:p.quantity==0)continue;if(filter.equals("Скоро")&&(p.daysLeft(today)<0||p.daysLeft(today)>1))continue;if(filter.equals("Просрочено")&&p.daysLeft(today)>=0)continue;
            productCard(p,today);shown++;}
        if(shown==0){Ui.space(this,body,20);body.addView(Ui.text(this,all.isEmpty()?"Здесь будут продукты вашей семьи":"В этом разделе пока пусто",22,Ui.INK));body.addView(Ui.text(this,all.isEmpty()?"Добавьте первую упаковку, срок и фото. Всё сохраняется на телефоне и работает без интернета.":"Можно посмотреть остальные продукты или добавить новую упаковку.",15,Ui.MUTED));}
    }
    private void productCard(Product p,LocalDate today){
        LinearLayout card=Ui.column(this);Ui.card(this,card);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=Ui.dp(this,12);body.addView(card,params);
        LinearLayout top=Ui.row(this);card.addView(top);
        if(!p.photo.isEmpty())try{byte[] bytes=android.util.Base64.decode(p.photo,android.util.Base64.DEFAULT);BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=4;Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);ImageView image=new ImageView(this);image.setImageBitmap(bitmap);image.setContentDescription("Фото: "+p.name);image.setScaleType(ImageView.ScaleType.CENTER_CROP);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(Ui.dp(this,64),Ui.dp(this,64));ip.rightMargin=Ui.dp(this,12);top.addView(image,ip);}catch(Exception ignored){}
        LinearLayout text=Ui.column(this);top.addView(text,new LinearLayout.LayoutParams(0,-2,1));TextView title=Ui.text(this,p.name,20,Ui.INK);title.setTypeface(null,Typeface.BOLD);text.addView(title);text.addView(Ui.text(this,p.quantity+" "+p.unit,14,Ui.MUTED));
        card.addView(Ui.text(this,p.status(today),14,p.daysLeft(today)<0?Ui.RED:Ui.GREEN));
        if(p.expiry!=null)card.addView(Ui.text(this,"Годен до "+p.expiry+ (p.opened!=null?" · открыто "+p.opened:""),12,Ui.MUTED));
        if(p.opened!=null&&!p.expiry.equals(p.printedExpiry))card.addView(Ui.text(this,"На упаковке: "+p.printedExpiry,12,Ui.MUTED));
        if(p.daysLeft(today)<0)card.addView(Ui.text(this,"Дополнительные дни не означают, что продукт безопасен.",12,Ui.RED));
        LinearLayout actions=Ui.row(this);card.addView(actions);
        if(p.quantity>0)actions.addView(Ui.button(this,p.opened==null?"Открыто · 3 дня":"Отменить открытие",()->change(p.id,Map.of("opened",p.opened==null?ReminderReceiver.today(this).toString():""))));
        if(p.quantity>0)actions.addView(Ui.button(this,"− 1 "+p.unit,()->consume(p.id)));
        LinearLayout more=Ui.row(this);card.addView(more);more.addView(Ui.button(this,"Изменить",()->startActivityForResult(new Intent(this,EditorActivity.class).putExtra("product",p.id),30)));
        more.addView(Ui.button(this,"Удалить",()->new AlertDialog.Builder(this).setTitle("Удалить «"+p.name+"»?").setMessage("Удаление будет передано второму телефону.").setPositiveButton("Удалить",(d,w)->change(p.id,Map.of("deleted","true"))).setNegativeButton("Отмена",null).show()));
    }
    private void change(String product,Map<String,String> fields){io.execute(()->{try{store.change(product,fields);runOnUiThread(()->{status="Сохранено на телефоне";render();});if(!store.invitation().relay.isEmpty())enqueueCloud();}catch(Exception e){runOnUiThread(()->Ui.error(this,e));}});}
    private void consume(String product){io.execute(()->{try{synchronized(store){Product p=store.products().stream().filter(x->x.id.equals(product)).findFirst().orElseThrow();if(p.quantity>0)store.change(product,Map.of("delta","-1"));}runOnUiThread(this::render);if(!store.invitation().relay.isEmpty())enqueueCloud();}catch(Exception e){runOnUiThread(()->Ui.error(this,e));}});}
    private void family(){
        body.addView(Ui.text(this,"Один холодильник — актуальный список у каждого",20,Ui.INK));
        body.addView(Ui.text(this,"Рядом: подключите оба телефона к одной Wi-Fi-сети и откройте приложение. Из разных мест: подключите HTTPS-ретранслятор ниже. Названия, сроки и фото передаются зашифрованными.",15,Ui.MUTED));
        body.addView(Ui.button(this,"Пригласить по QR / ссылке",this::invite));
        body.addView(Ui.button(this,"Сканировать приглашение",()->startActivityForResult(new Intent(this,ScanActivity.class),31)));
        body.addView(Ui.button(this,"Вставить ссылку приглашения",()->{EditText input=new EditText(this);input.setHint("fridgedays://join#…");new AlertDialog.Builder(this).setTitle("Ссылка семьи").setView(input).setPositiveButton("Присоединиться",(d,w)->join(input.getText().toString().trim())).setNegativeButton("Отмена",null).show();}));
        body.addView(Ui.button(this,"Повторить обмен рядом",()->{stopNearby();startNearby();}));
        Ui.space(this,body,16);body.addView(Ui.text(this,"Обмен через интернет",20,Ui.INK));
        body.addView(Ui.text(this,"Укажите адрес семейного сервиса синхронизации. Пока он не подключён, можно пользоваться обменом рядом. Бесплатное облако имеет лимиты; доступность нужно проверить в ваших сетях.",14,Ui.MUTED));
        EditText relay=Ui.field(this,"HTTPS-адрес ретранслятора",store.invitation().relay,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI,body);
        body.addView(Ui.button(this,"Сохранить адрес",()->{try{Invitation old=store.invitation();Invitation next=new Invitation(old.family,old.fridge,old.name,relay.getText().toString().trim(),old.key);store.join(next);SyncJob.schedule(this,store);status=next.relay.isEmpty()?"Облако отключено":"Адрес сохранён · передайте обновлённое приглашение второму телефону";render();if(!next.relay.isEmpty())syncCloud();}catch(Exception e){Ui.error(this,e);}}));
        body.addView(Ui.button(this,"Синхронизировать сейчас",this::syncCloud));
        body.addView(Ui.button(this,"Параметры регистрации ретранслятора",()->{try{Invitation i=store.invitation();String json="{\""+i.room()+"\":\""+Crypto.sha256(Crypto.capability(i.key,i.room()))+"\"}";TextView text=Ui.text(this,json,13,Ui.INK);text.setTextIsSelectable(true);text.setPadding(24,16,24,16);new AlertDialog.Builder(this).setTitle("Значение ROOMS для Worker").setView(text).setPositiveButton("Копировать",(d,w)->{android.content.ClipboardManager clip=getSystemService(android.content.ClipboardManager.class);clip.setPrimaryClip(ClipData.newPlainText("ROOMS",json));}).setNegativeButton("Закрыть",null).show();}catch(Exception e){Ui.error(this,e);}}));
        body.addView(Ui.text(this,"QR и ссылка содержат ключ семьи. Не публикуйте их. В первом релизе нет отзыва доступа отдельного участника.",13,Ui.RED));
    }
    private void invite(){try{
        Invitation i=store.invitation();BitMatrix matrix=new MultiFormatWriter().encode(i.link(),BarcodeFormat.QR_CODE,650,650,Map.of(EncodeHintType.MARGIN,2));Bitmap bitmap=Bitmap.createBitmap(650,650,Bitmap.Config.ARGB_8888);int[] pixels=new int[650*650];for(int y=0;y<650;y++)for(int x=0;x<650;x++)pixels[y*650+x]=matrix.get(x,y)?Color.BLACK:Color.WHITE;bitmap.setPixels(pixels,0,650,0,0,650,650);
        LinearLayout content=Ui.column(this);content.setPadding(24,16,24,16);ImageView qr=new ImageView(this);qr.setImageBitmap(bitmap);qr.setAdjustViewBounds(true);qr.setContentDescription("QR приглашения в семью");content.addView(qr,new LinearLayout.LayoutParams(-1,Ui.dp(this,280)));content.addView(Ui.text(this,"Покажите QR второму телефону. Он даёт доступ ко всем данным этого холодильника.",14,Ui.MUTED));
        new AlertDialog.Builder(this).setTitle("Ваш общий холодильник").setView(content).setPositiveButton("Поделиться ссылкой",(d,w)->startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,i.link()),"Приглашение семье"))).setNegativeButton("Закрыть",null).show();
    }catch(Exception e){Ui.error(this,e);}}
    private void join(String link){try{Invitation next=Invitation.parse(link);stopNearby();store.join(next);SyncJob.schedule(this,store);status="Семья подключена · дождитесь обмена данными";tab="fridge";render();startNearby();if(!next.relay.isEmpty())syncCloud();}catch(Exception e){startNearby();Ui.error(this,e);}}
    private void settings(){
        body.addView(Ui.text(this,"Утренняя сводка",21,Ui.INK));body.addView(Ui.text(this,"Сегодня, завтра и просроченные продукты. Работает по локальному списку без интернета. Android может задержать доставку в режиме экономии энергии.",14,Ui.MUTED));
        var prefs=getSharedPreferences("settings",0);EditText hour=Ui.field(this,"Час уведомления (0–23)",Integer.toString(prefs.getInt("hour",9)),InputType.TYPE_CLASS_NUMBER,body);
        EditText zone=Ui.field(this,"Часовой пояс семьи",prefs.getString("zone","Europe/Moscow"),InputType.TYPE_CLASS_TEXT,body);
        body.addView(Ui.text(this,"Например Europe/Moscow, Asia/Yekaterinburg, Asia/Novosibirsk. Укажите одинаковый пояс на обоих телефонах.",12,Ui.MUTED));
        body.addView(Ui.button(this,"Сохранить уведомления",()->{try{int h=Integer.parseInt(hour.getText().toString());if(h<0||h>23)throw new IllegalArgumentException("Час: от 0 до 23");String z=zone.getText().toString().trim();ZoneId.of(z);prefs.edit().putInt("hour",h).putString("zone",z).apply();ReminderReceiver.schedule(this);Toast.makeText(this,"Расписание сохранено",Toast.LENGTH_SHORT).show();}catch(Exception e){Ui.error(this,e);}}));
        body.addView(Ui.button(this,"Разрешить уведомления",()->{if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},40);else startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName()));}));
        body.addView(Ui.button(this,"Проверить сводку сейчас",()->{try{ReminderReceiver.send(this,store,true);}catch(Exception e){Ui.error(this,e);}}));
        Ui.space(this,body,18);body.addView(Ui.text(this,"Резервная копия",21,Ui.INK));body.addView(Ui.text(this,"Фото, сроки и ключ семьи шифруются паролем. Без пароля восстановить копию нельзя. Она не заменяет автоматический обмен между телефонами.",14,Ui.MUTED));
        body.addView(Ui.button(this,"Сохранить зашифрованную копию",()->password(false)));body.addView(Ui.button(this,"Восстановить копию",()->password(true)));
        body.addView(Ui.text(this,"Версия 0.1 · Android 12+ · хранение на телефоне",12,Ui.MUTED));
    }
    private void password(boolean importing){EditText input=new EditText(this);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);input.setHint("Не менее 12 символов");new AlertDialog.Builder(this).setTitle(importing?"Пароль копии":"Задайте пароль копии").setView(input).setPositiveButton("Продолжить",(d,w)->{
        String p=input.getText().toString();if(p.length()<12){Ui.error(this,new IllegalArgumentException("Пароль должен содержать минимум 12 символов"));return;}if(backupPassword!=null)Arrays.fill(backupPassword,'\0');backupPassword=p.toCharArray();pendingImport=importing;
        Intent file=importing?new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"):new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,"fridge-"+ReminderReceiver.today(this)+".fridge");startActivityForResult(file,32);
    }).setNegativeButton("Отмена",null).show();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(result!=RESULT_OK){if(request==32&&backupPassword!=null){Arrays.fill(backupPassword,'\0');backupPassword=null;}return;}
        if(request==30){status="Сохранено на телефоне";render();if(store!=null&&!store.invitation().relay.isEmpty())syncCloud();if(Build.VERSION.SDK_INT>=33&&!getPreferences(0).getBoolean("asked-notifications",false)){getPreferences(0).edit().putBoolean("asked-notifications",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},40);}}
        if(request==31&&data!=null)join(data.getStringExtra("invitation"));
        if(request==32&&data!=null&&data.getData()!=null){if(backupPassword==null){Ui.error(this,new IllegalStateException("Снова выберите файл и введите пароль"));return;}char[] secret=backupPassword;backupPassword=null;boolean importing=pendingImport;Uri uri=data.getData();
            io.execute(()->{try{
                if(importing){byte[] bytes;try(InputStream in=getContentResolver().openInputStream(uri)){bytes=CloudSync.read(in,68_000_000);}Backup backup=Backup.parse(Crypto.passwordOpen(secret,new String(bytes,java.nio.charset.StandardCharsets.US_ASCII)));
                    // Verify archive integrity in full before adopting its key or committing operations.
                    Ledger check=new Ledger();List<Operation> ops=new ArrayList<>();for(var e:backup.packets.entrySet()){Operation op=Codec.operation(Crypto.open(backup.invitation.key,backup.invitation.context(e.getKey()),e.getValue()));if(!op.id.equals(e.getKey()))throw new IOException("ID не совпадает");ops.add(op);}check.merge(ops);
                    synchronized(store){store.join(backup.invitation);store.merge(backup.packets);}runOnUiThread(()->{stopNearby();startNearby();SyncJob.schedule(this,store);});
                }else{Backup backup;synchronized(store){backup=new Backup(store.invitation(),store.packets());}String sealed=Crypto.passwordSeal(secret,backup.bytes());try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){out.write(sealed.getBytes(java.nio.charset.StandardCharsets.US_ASCII));}}
                runOnUiThread(()->{status=importing?"Копия восстановлена":"Зашифрованная копия сохранена";render();});
            }catch(Exception e){runOnUiThread(()->Ui.error(this,e));}finally{Arrays.fill(secret,'\0');}});
        }
    }
    private void startNearby(){if(!visible||store==null||nearby!=null)return;try{nearby=new NearbySync(this,store,message->runOnUiThread(()->{status=message;if(visible)render();}));}catch(Exception e){status="Поиск рядом недоступен в этой сети";if(statusView!=null)statusView.setText(status);}}
    private void stopNearby(){if(nearby!=null){nearby.close();nearby=null;}}
    private void syncCloud(){if(store==null)return;status="Проверяем синхронизацию…";if(statusView!=null)statusView.setText(status);enqueueCloud();}
    private void enqueueCloud(){if(network.isShutdown()||!cloudRequested.compareAndSet(false,true))return;try{network.execute(()->{try{syncCloudNow();}finally{cloudRequested.set(false);}});}catch(RejectedExecutionException ignored){cloudRequested.set(false);}}
    private void syncCloudNow(){try{String message=CloudSync.run(store);runOnUiThread(()->{status=message;if(visible)render();});}catch(Exception e){runOnUiThread(()->{status="Нет связи с облаком · изменения сохранены на телефоне";if(statusView!=null)statusView.setText(status);});}}
}
