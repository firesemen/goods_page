package ru.fridgedays.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.time.*;
import java.util.*;
import java.io.*;
import ru.fridgedays.core.*;

public final class EditorActivity extends Activity {
    private Store store;private Product original;private String product,photo="";private Uri cameraUri;
    private EditText name,count,expiry,made,shelf,barcodeInput;private ImageView photoPreview;private TextView barcodeStatus;private ProductLookup lookupJob;private Spinner unit,openingDays;private TextView photoLabel;private Button save;
    private final java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ExecutorService recognition=java.util.concurrent.Executors.newSingleThreadExecutor();
    private final List<Button> photoButtons=new ArrayList<>();
    private TextView ocrStatus;private Button cancelRecognition;private boolean datePhoto,recognizeAfter,processing,recognizing,destroyed;private int generation;
    private PhotoCloudClient cloudJob;private AlertDialog reviewDialog;
    @Override public void onCreate(Bundle state){super.onCreate(state);
        try{store=Store.get(this);product=state==null?getIntent().getStringExtra("product"):state.getString("product");if(product!=null)for(Product p:store.products())if(p.id.equals(product))original=p;if(product==null)product=UUID.randomUUID().toString();
            if(original!=null)photo=original.photo;
            LinearLayout root=Ui.column(this);Ui.inset(this,root);ScrollView scroll=new ScrollView(this);root.addView(scroll);LinearLayout form=Ui.column(this);form.setPadding(Ui.dp(this,20),Ui.dp(this,12),Ui.dp(this,20),Ui.dp(this,32));scroll.addView(form);setContentView(root);
            form.addView(Ui.button(this,"‹ Назад",this::finish));form.addView(Ui.title(this,original==null?"Новая упаковка":"Изменить продукт"));
            barcodeInput=Ui.field(this,"Штрихкод продукта (необязательно)",original==null?"":original.barcode,InputType.TYPE_CLASS_NUMBER,form);barcodeInput.setId(R.id.barcode_input);
            addPhotoButton(form,"Сканировать код",()->startActivityForResult(new Intent(this,ScanActivity.class).putExtra("mode","product"),24)).setId(R.id.barcode_scan);
            addPhotoButton(form,"Найти по коду",this::lookupBarcode).setId(R.id.barcode_lookup);
            barcodeStatus=Ui.text(this,"Сначала ищем в семейной истории. Новый товар можно найти в Open Food Facts; туда отправляется только штрихкод",12,Ui.MUTED);form.addView(barcodeStatus);
            name=Ui.field(this,"Название",original==null?"":original.name,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,form);
            name.setId(R.id.product_name_input);
            count=Ui.field(this,"Сколько осталось в упаковке",original==null?"1":Amounts.display(original.quantityMilli),InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL,form);count.setId(R.id.quantity_input);count.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789.,"));
            String currentUnit=original==null?"шт":original.unit;List<String> units=Ui.units(currentUnit);unit=Ui.dropdown(this,"Единица количества",units,units.indexOf(currentUnit),form);unit.setId(R.id.unit_selector);
            openingDays=Ui.dropdown(this,"Срок после открытия",Ui.dayOptions(),original==null?2:original.openingDays-1,form);openingDays.setId(R.id.opening_days_selector);
            expiry=Ui.field(this,"Годен до (включительно)",original==null||original.printedExpiry==null?"":original.printedExpiry.toString(),InputType.TYPE_CLASS_DATETIME,form);datePicker(expiry,form);
            expiry.setId(R.id.expiry_input);
            made=Ui.field(this,"Или дата изготовления", "",InputType.TYPE_CLASS_DATETIME,form);datePicker(made,form);
            made.setId(R.id.manufactured_input);
            shelf=Ui.field(this,"Срок хранения в днях", "",InputType.TYPE_CLASS_NUMBER,form);
            shelf.setId(R.id.shelf_input);
            form.addView(Ui.button(this,"Рассчитать дату годности",()->{try{int d=Integer.parseInt(shelf.getText().toString());if(d<1||d>100_000)throw new IllegalArgumentException("Укажите срок от 1 до 100000 дней");expiry.setText(LocalDate.parse(made.getText().toString()).plusDays(d).toString());}catch(Exception e){Ui.error(this,new IllegalArgumentException("Укажите дату изготовления и положительный срок в днях"));}}));
            form.addView(Ui.text(this,"Дата изготовления + число дней. Если на упаковке указано «годен до», используйте именно её.",12,Ui.MUTED));
            photoPreview=new ImageView(this);photoPreview.setAdjustViewBounds(true);photoPreview.setMaxHeight(Ui.dp(this,220));photoPreview.setContentDescription("Фото продукта");form.addView(photoPreview);showPhoto();
            photoLabel=Ui.text(this,photo.isEmpty()?"Фото не добавлено":"Фото добавлено",14,Ui.MUTED);form.addView(photoLabel);
            addPhotoButton(form,"Выбрать фото",()->pickPhoto(false,false));
            addPhotoButton(form,"Сфотографировать",()->{datePhoto=false;recognizeAfter=false;takePhoto();});
            addPhotoButton(form,"Убрать фото",()->{photo="";showPhoto();photoLabel.setText("Фото не добавлено");try{java.nio.file.Files.write(draft().toPath(),new byte[0]);}catch(IOException e){Ui.error(this,e);}PhotoImages.clear(this,product);ocrStatus.setText("Снимки удалены. Можно заполнить вручную");});
            Ui.space(this,form,12);form.addView(Ui.text(this,"Заполнить по фото",21,Ui.INK));
            form.addView(Ui.text(this,"Снимите название и количество. Для даты можно добавить отдельный крупный снимок. После распознавания проверьте поля; карточка сохранится только по кнопке внизу.",14,Ui.MUTED));
            addPhotoButton(form,"Снять срок крупно",()->{datePhoto=true;recognizeAfter=false;takePhoto();});
            addPhotoButton(form,"Выбрать снимок срока",()->pickPhoto(true,false));
            addPhotoButton(form,"Распознать через Cloudflare",this::confirmCloud).setId(R.id.ocr_cloud);
            ocrStatus=Ui.text(this,"Распознавание через Cloudflare · нужен интернет. Снимки отправляются только после подтверждения",13,Ui.MUTED);ocrStatus.setId(R.id.ocr_status);form.addView(ocrStatus);
            cancelRecognition=Ui.button(this,"Остановить распознавание",this::cancelRecognition);cancelRecognition.setId(R.id.ocr_cancel);cancelRecognition.setVisibility(View.GONE);form.addView(cancelRecognition);
            save=Ui.button(this,"Сохранить упаковку",this::save);form.addView(save);
            if(state!=null){name.setText(state.getString("name"));count.setText(state.getString("count"));unit.setSelection(Math.max(0,units.indexOf(state.getString("unit",currentUnit))));openingDays.setSelection(state.getInt("openingDays",2));expiry.setText(state.getString("expiry"));made.setText(state.getString("made"));shelf.setText(state.getString("shelf"));barcodeInput.setText(state.getString("barcode",barcodeInput.getText().toString()));String camera=state.getString("camera");cameraUri=camera==null?null:Uri.parse(camera);
                datePhoto=state.getBoolean("datePhoto");recognizeAfter=state.getBoolean("recognizeAfter");
                File f=draft();if(f.exists())photo=new String(java.nio.file.Files.readAllBytes(f.toPath()),java.nio.charset.StandardCharsets.US_ASCII);else if(state.getBoolean("photoEmpty",false))photo="";showPhoto();photoLabel.setText(photo.isEmpty()?"Фото не добавлено":"Фото добавлено");}
            if(cameraUri==null){String pending=getSharedPreferences("pending-capture",0).getString(product,null);if(pending!=null)cameraUri=Uri.parse(pending);}
        }catch(Exception e){Ui.error(this,e);}
    }
    private void showPhoto(){if(photoPreview==null)return;if(photo.isEmpty()){photoPreview.setImageDrawable(null);photoPreview.setVisibility(View.GONE);return;}try{byte[] bytes=android.util.Base64.decode(photo,android.util.Base64.DEFAULT);photoPreview.setImageBitmap(BitmapFactory.decodeByteArray(bytes,0,bytes.length));photoPreview.setVisibility(View.VISIBLE);}catch(IllegalArgumentException ignored){photoPreview.setVisibility(View.GONE);}}
    private void lookupBarcode(){try{
        String code=Barcode.canonical(barcodeInput.getText().toString());barcodeInput.setText(code);Product known=store.byBarcode(code);
        if(known!=null){barcodeStatus.setText("Найдено в семейной истории");reviewDialog=PhotoReviewDialog.show(this,store.barcodeDraft(known),this::applySuggestions);return;}
        new AlertDialog.Builder(this).setTitle("Новый товар для семьи").setMessage("Найти название и количество в Open Food Facts? В базу уйдёт только штрихкод. После сохранения упаковки товар будет доступен из семейной истории.").setPositiveButton("Искать в базе",(d,w)->lookupExternal(code)).setNegativeButton("Заполнить вручную",null).show();
    }catch(Exception e){Ui.error(this,e);}}
    private void lookupExternal(String code){if(recognizing||processing)return;recognizing=true;int request=++generation;ProductLookup client=new ProductLookup();lookupJob=client;ocrStatus.setText("Поиск в базе товаров…");busy();recognition.execute(()->{try{
        PhotoDraft draft=client.find(code);runOnUiThread(()->{if(destroyed||generation!=request)return;recognizing=false;lookupJob=null;busy();
            if(!barcodeInput.getText().toString().equals(code)){barcodeStatus.setText("Код изменился. Выполните поиск заново");return;}
            if(draft==null){barcodeStatus.setText("Товара нет в базе. Заполните название вручную или по фото; после сохранения он появится в семейной истории");ocrStatus.setText("Можно заполнить вручную или по фото");return;}
            barcodeStatus.setText(R.string.barcode_database_result);ocrStatus.setText("Данные найдены. Срок годности задайте отдельно");reviewDialog=PhotoReviewDialog.show(this,draft,this::applySuggestions);
        });
    }catch(Exception e){runOnUiThread(()->{if(destroyed||generation!=request)return;recognizing=false;lookupJob=null;busy();barcodeStatus.setText("Поиск не завершён. Можно заполнить вручную или по фото");if(!(e instanceof java.util.concurrent.CancellationException))Ui.error(this,e);});}});}
    private File draft(){return new File(getCacheDir(),"photo-"+product+".txt");}
    private Button addPhotoButton(LinearLayout form,String title,Runnable action){Button button=Ui.button(this,title,action);photoButtons.add(button);form.addView(button);return button;}
    private void busy(){if(save==null)return;save.setEnabled(!processing&&!recognizing);for(Button b:photoButtons)b.setEnabled(!processing&&!recognizing);cancelRecognition.setVisibility(recognizing?View.VISIBLE:View.GONE);}
    private boolean hasImages(){return !photo.isEmpty()||PhotoImages.source(this,product,false).isFile()||PhotoImages.source(this,product,true).isFile();}
    private void pickPhoto(boolean date,boolean recognize){datePhoto=date;recognizeAfter=recognize;Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(pick,21);}
    private void choosePhoto(boolean date,boolean recognize){new AlertDialog.Builder(this).setTitle(date?"Маркировка срока":"Фото упаковки").setItems(new String[]{"Сфотографировать","Выбрать фото"},(d,w)->{datePhoto=date;recognizeAfter=recognize;if(w==0)takePhoto();else pickPhoto(date,recognize);}).show();}
    private void takePhoto(){
        if(checkSelfPermission(android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.CAMERA},23);return;}
        try{cleanCamera();cameraUri=CaptureProvider.create(this);
            if(!getSharedPreferences("pending-capture",0).edit().putString(product,cameraUri.toString()).commit())throw new IOException("Не удалось сохранить состояние камеры");
            Intent camera=new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT,cameraUri).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);camera.setClipData(ClipData.newRawUri("Снимок",cameraUri));startActivityForResult(camera,22);
        }catch(Exception e){cleanCamera();Ui.error(this,e);}
    }
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] grants){super.onRequestPermissionsResult(r,p,grants);if(r==23&&grants.length>0&&grants[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)takePhoto();}
    private void deleteDraft(){if(draft().exists())draft().delete();}
    private void datePicker(EditText field,LinearLayout form){form.addView(Ui.button(this,"Выбрать дату",()->{LocalDate initial;try{initial=LocalDate.parse(field.getText().toString());}catch(Exception e){initial=ReminderReceiver.today(this);}new DatePickerDialog(this,(v,y,m,d)->field.setText(LocalDate.of(y,m+1,d).toString()),initial.getYear(),initial.getMonthValue()-1,initial.getDayOfMonth()).show();}));}
    private void cleanCamera(){if(cameraUri!=null){if((getPackageName()+".capture").equals(cameraUri.getAuthority()))CaptureProvider.remove(this,cameraUri);else try{getContentResolver().delete(cameraUri,null,null);}catch(Exception ignored){}}cameraUri=null;if(product!=null)getSharedPreferences("pending-capture",0).edit().remove(product).apply();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==24){if(result==RESULT_OK&&data!=null){barcodeInput.setText(data.getStringExtra("barcode"));lookupBarcode();}return;}if(request!=21&&request!=22)return;
        if(result!=RESULT_OK){if(request==22)cleanCamera();return;}Uri uri=request==22?cameraUri:data==null?null:data.getData();
        if(request==22&&!CaptureProvider.ready(this,uri)&&data!=null&&data.getData()!=null)uri=data.getData();
        if(uri==null){Ui.error(this,new IOException("Камера не вернула снимок. Попробуйте ещё раз или выберите фото из галереи"));return;}final Uri inputUri=uri;
        final boolean isDate=datePhoto,runAfter=recognizeAfter;recognizeAfter=false;processing=true;photoLabel.setText("Обработка фото…");busy();
        io.execute(()->{try{
            String encoded=PhotoImages.importImage(this,product,isDate,inputUri);
            if(!isDate){PhotoImages.source(this,product,true).delete();java.nio.file.Files.write(draft().toPath(),encoded.getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
            if(request==22)cleanCamera();runOnUiThread(()->{if(destroyed)return;processing=false;if(!isDate)photo=encoded;showPhoto();photoLabel.setText(isDate?"Снимок срока добавлен · основное фото сохранено":"Фото добавлено");ocrStatus.setText("Снимок готов к распознаванию");busy();if(runAfter)confirmCloud();});
        }catch(Exception e){runOnUiThread(()->{if(destroyed)return;processing=false;photoLabel.setText("Фото не обработано");busy();Ui.error(this,e);});}finally{if(request==22&&cameraUri!=null)cleanCamera();}});
    }
    private void confirmCloud(){
        if(PhotoCloudClient.endpoint(this).isEmpty()){new AlertDialog.Builder(this).setTitle("Настройте Cloudflare").setMessage("Сначала укажите адрес Worker в настройках распознавания. Фото останутся на телефоне, пока вы не подтвердите отправку. Карточку можно заполнить вручную.").setPositiveButton("Настроить",(d,w)->startActivity(new Intent(this,PhotoSettingsActivity.class))).setNegativeButton("Закрыть",null).show();return;}
        if(!hasImages()){choosePhoto(false,true);return;}
        new AlertDialog.Builder(this).setTitle("Отправить снимки?").setMessage("Основное фото и добавленный снимок срока уйдут по HTTPS вашему Worker и модели Workers AI. На них могут быть личные данные — проверьте кадр. Автоматического повтора запроса нет.").setPositiveButton("Отправить",(d,w)->recognize()).setNegativeButton("Отмена",null).show();
    }
    private void recognize(){
        if(recognizing||processing)return;recognizing=true;int request=++generation;String currentPhoto=photo;String endpoint=PhotoCloudClient.endpoint(this);Invitation family=store.invitation();
        cloudJob=new PhotoCloudClient();PhotoCloudClient remote=cloudJob;
        ocrStatus.setText(R.string.cloud_recognition_in_progress);busy();
        recognition.execute(()->{try{
            List<File> images=new ArrayList<>();File front=PhotoImages.source(this,product,false),date=PhotoImages.source(this,product,true);
            if(!front.exists()&&!currentPhoto.isEmpty())java.nio.file.Files.write(front.toPath(),android.util.Base64.decode(currentPhoto,android.util.Base64.DEFAULT));
            if(front.isFile())images.add(front);if(date.isFile())images.add(date);if(images.isEmpty())throw new IOException("Сначала добавьте снимок упаковки или срока");
            PhotoDraft draft=remote.recognize(endpoint,family,images);
            runOnUiThread(()->{if(destroyed||generation!=request)return;recognizing=false;cloudJob=null;busy();ocrStatus.setText("Распознано · проверьте данные по упаковке");reviewDialog=PhotoReviewDialog.show(this,draft,this::applySuggestions);});
        }catch(Exception e){runOnUiThread(()->{if(destroyed||generation!=request)return;recognizing=false;cloudJob=null;busy();ocrStatus.setText("Распознавание не завершено. Можно заполнить вручную");if(!(e instanceof java.util.concurrent.CancellationException))Ui.error(this,e);});}});
    }
    private void applySuggestions(Map<String,String> fields){
        if(fields.containsKey("name"))name.setText(fields.get("name"));
        if(fields.containsKey("quantity")){count.setText(fields.get("quantity"));List<String> options=Ui.units(fields.get("unit"));ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,options);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);unit.setAdapter(adapter);unit.setSelection(options.indexOf(fields.get("unit")));}
        if(fields.containsKey("openingDays"))openingDays.setSelection(Integer.parseInt(fields.get("openingDays"))-1);
        if(fields.containsKey("expiry"))expiry.setText(fields.get("expiry"));
        if(fields.containsKey("manufactured"))made.setText(fields.get("manufactured"));
        if(fields.containsKey("shelfDays"))shelf.setText(fields.get("shelfDays"));
        ocrStatus.setText("Данные перенесены в форму. Проверьте их и сохраните упаковку");
    }
    private void cancelRecognition(){++generation;if(cloudJob!=null)cloudJob.cancel();if(lookupJob!=null)lookupJob.cancel();lookupJob=null;cloudJob=null;recognizing=false;if(ocrStatus!=null)ocrStatus.setText("Распознавание остановлено. Поля не изменены");busy();}
    private void save(){try{
        if(processing||recognizing)return;
        String title=name.getText().toString().trim(),u=unit.getSelectedItem().toString();long qty=Amounts.parse(count.getText().toString());int days=openingDays.getSelectedItemPosition()+1;LocalDate date=LocalDate.parse(expiry.getText().toString());
        if(title.isEmpty()||qty<(original==null?1:0))throw new IllegalArgumentException("Укажите название и положительное количество; для существующей упаковки допускается 0");
        if(Amounts.countable(u)&&qty%1000!=0)throw new IllegalArgumentException("Для штук, упаковок и порций укажите целое количество");
        if(!made.getText().toString().trim().isEmpty()&&date.isBefore(LocalDate.parse(made.getText().toString().trim())))throw new IllegalArgumentException("Срок годности раньше даты изготовления. Проверьте даты");
        Map<String,String> fields=new HashMap<>();fields.put("barcode",barcodeInput.getText().toString().trim().isEmpty()?"":Barcode.canonical(barcodeInput.getText().toString()));fields.put("name",title);fields.put("unit",u);fields.put("expiry",date.toString());fields.put("photo",photo);fields.put("openingDays",Integer.toString(days));
        if(original==null){fields.put("initialQuantity",Amounts.format(qty));if(fields.get("barcode").isEmpty())fields.remove("barcode");}
        save.setEnabled(false);io.execute(()->{try{
            if(original!=null){
                if(fields.get("barcode").equals(original.barcode))fields.remove("barcode");
                if(title.equals(original.name))fields.remove("name");
                if(u.equals(original.unit))fields.remove("unit");
                if(date.equals(original.printedExpiry))fields.remove("expiry");
                if(photo.equals(original.photo))fields.remove("photo");
                if(days==original.openingDays)fields.remove("openingDays");
            }
            if(!fields.isEmpty())store.change(product,fields);
            if(original!=null){long delta=qty-original.quantityMilli;if(delta!=0)store.change(product,Map.of("delta",Amounts.format(delta)));}
            deleteDraft();PhotoImages.clear(this,product);runOnUiThread(()->{setResult(RESULT_OK);finish();});
        }catch(Exception e){runOnUiThread(()->{save.setEnabled(true);Ui.error(this,e);});}});
    }catch(Exception e){Ui.error(this,e);}}
    @Override protected void onSaveInstanceState(Bundle s){super.onSaveInstanceState(s);if(name!=null){s.putString("barcode",barcodeInput.getText().toString());s.putString("product",product);s.putString("name",name.getText().toString());s.putString("count",count.getText().toString());s.putString("unit",unit.getSelectedItem().toString());s.putInt("openingDays",openingDays.getSelectedItemPosition());s.putBoolean("photoEmpty",photo.isEmpty());s.putString("expiry",expiry.getText().toString());s.putString("made",made.getText().toString());s.putString("shelf",shelf.getText().toString());s.putBoolean("datePhoto",datePhoto);s.putBoolean("recognizeAfter",recognizeAfter);if(cameraUri!=null)s.putString("camera",cameraUri.toString());}}
    @Override protected void onStop(){super.onStop();if(recognizing)cancelRecognition();if(reviewDialog!=null)reviewDialog.dismiss();}
    @Override protected void onDestroy(){destroyed=true;cancelRecognition();recognition.shutdown();if(isFinishing())io.execute(()->{deleteDraft();PhotoImages.clear(this,product);cleanCamera();});io.shutdown();super.onDestroy();}
}
