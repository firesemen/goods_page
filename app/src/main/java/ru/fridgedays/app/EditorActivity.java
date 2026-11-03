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
    private EditText name,count,unit,expiry,made,shelf;private TextView photoLabel;private Button save;
    private final java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle state){super.onCreate(state);
        try{store=Store.get(this);product=state==null?getIntent().getStringExtra("product"):state.getString("product");if(product!=null)for(Product p:store.products())if(p.id.equals(product))original=p;if(product==null)product=UUID.randomUUID().toString();
            if(original!=null)photo=original.photo;
            LinearLayout root=Ui.column(this);Ui.inset(this,root);ScrollView scroll=new ScrollView(this);root.addView(scroll);LinearLayout form=Ui.column(this);form.setPadding(Ui.dp(this,20),Ui.dp(this,12),Ui.dp(this,20),Ui.dp(this,32));scroll.addView(form);setContentView(root);
            form.addView(Ui.button(this,"‹ Назад",this::finish));form.addView(Ui.title(this,original==null?"Новая упаковка":"Изменить продукт"));
            name=Ui.field(this,"Название",original==null?"":original.name,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,form);
            count=Ui.field(this,"Сколько осталось в упаковке",original==null?"1":String.valueOf(original.quantity),InputType.TYPE_CLASS_NUMBER,form);
            unit=Ui.field(this,"Единица количества",original==null?"шт.":original.unit,InputType.TYPE_CLASS_TEXT,form);
            expiry=Ui.field(this,"Годен до (включительно)",original==null||original.printedExpiry==null?"":original.printedExpiry.toString(),InputType.TYPE_CLASS_DATETIME,form);datePicker(expiry,form);
            made=Ui.field(this,"Или дата изготовления", "",InputType.TYPE_CLASS_DATETIME,form);datePicker(made,form);
            shelf=Ui.field(this,"Срок хранения в днях", "",InputType.TYPE_CLASS_NUMBER,form);
            form.addView(Ui.button(this,"Рассчитать дату годности",()->{try{int d=Integer.parseInt(shelf.getText().toString());if(d<1||d>100_000)throw new IllegalArgumentException("Укажите срок от 1 до 100000 дней");expiry.setText(LocalDate.parse(made.getText().toString()).plusDays(d).toString());}catch(Exception e){Ui.error(this,new IllegalArgumentException("Укажите дату изготовления и положительный срок в днях"));}}));
            form.addView(Ui.text(this,"Дата изготовления + число дней. Если на упаковке указано «годен до», используйте именно её.",12,Ui.MUTED));
            photoLabel=Ui.text(this,photo.isEmpty()?"Фото не добавлено":"Фото добавлено",14,Ui.MUTED);form.addView(photoLabel);
            form.addView(Ui.button(this,"Выбрать фото",()->{Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(pick,21);}));
            form.addView(Ui.button(this,"Сфотографировать",this::takePhoto));
            form.addView(Ui.button(this,"Убрать фото",()->{photo="";photoLabel.setText("Фото не добавлено");deleteDraft();}));
            save=Ui.button(this,"Сохранить упаковку",this::save);form.addView(save);
            if(state!=null){name.setText(state.getString("name"));count.setText(state.getString("count"));unit.setText(state.getString("unit"));expiry.setText(state.getString("expiry"));made.setText(state.getString("made"));shelf.setText(state.getString("shelf"));String camera=state.getString("camera");cameraUri=camera==null?null:Uri.parse(camera);
                File f=draft();if(f.exists())photo=new String(java.nio.file.Files.readAllBytes(f.toPath()),java.nio.charset.StandardCharsets.US_ASCII);photoLabel.setText(photo.isEmpty()?"Фото не добавлено":"Фото добавлено");}
        }catch(Exception e){Ui.error(this,e);}
    }
    private File draft(){return new File(getCacheDir(),"photo-"+product+".txt");}
    private void takePhoto(){
        if(checkSelfPermission(android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.CAMERA},23);return;}
        try{ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,"fridge-"+UUID.randomUUID()+".jpg");values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");values.put(MediaStore.Images.Media.IS_PENDING,1);
            cameraUri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);if(cameraUri==null)throw new IOException("Не удалось подготовить фото");Intent camera=new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT,cameraUri).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivityForResult(camera,22);
        }catch(Exception e){cleanCamera();Ui.error(this,e);}
    }
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] grants){super.onRequestPermissionsResult(r,p,grants);if(r==23&&grants.length>0&&grants[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)takePhoto();}
    private void deleteDraft(){if(draft().exists())draft().delete();}
    private void datePicker(EditText field,LinearLayout form){form.addView(Ui.button(this,"Выбрать дату",()->{LocalDate initial;try{initial=LocalDate.parse(field.getText().toString());}catch(Exception e){initial=ReminderReceiver.today(this);}new DatePickerDialog(this,(v,y,m,d)->field.setText(LocalDate.of(y,m+1,d).toString()),initial.getYear(),initial.getMonthValue()-1,initial.getDayOfMonth()).show();}));}
    private void cleanCamera(){if(cameraUri!=null)try{getContentResolver().delete(cameraUri,null,null);}catch(Exception ignored){}cameraUri=null;}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=21&&request!=22)return;
        if(result!=RESULT_OK){if(request==22)cleanCamera();return;}Uri uri=request==22?cameraUri:data==null?null:data.getData();if(uri==null)return;
        photoLabel.setText("Обработка фото…");save.setEnabled(false);
        io.execute(()->{try{
            ImageDecoder.Source source=ImageDecoder.createSource(getContentResolver(),uri);Bitmap bitmap=ImageDecoder.decodeBitmap(source,(decoder,info,s)->{int w=info.getSize().getWidth(),h=info.getSize().getHeight();float scale=Math.min(1f,800f/Math.max(w,h));decoder.setTargetSize(Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)));decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);});
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();int quality=80;do{bytes.reset();bitmap.compress(Bitmap.CompressFormat.JPEG,quality,bytes);quality-=10;}while(bytes.size()>170_000&&quality>=20);bitmap.recycle();
            if(bytes.size()>170_000)throw new IOException("Выберите более простое фото");String encoded=android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP);
            java.nio.file.Files.write(draft().toPath(),encoded.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            runOnUiThread(()->{photo=encoded;photoLabel.setText(getString(R.string.photo_ready,bytes.size()/1024));save.setEnabled(true);});
        }catch(Exception e){runOnUiThread(()->{photoLabel.setText("Фото не обработано");save.setEnabled(true);Ui.error(this,e);});}finally{if(request==22)cleanCamera();}});
    }
    private void save(){try{
        String title=name.getText().toString().trim(),u=unit.getText().toString().trim();int qty=Integer.parseInt(count.getText().toString());LocalDate date=LocalDate.parse(expiry.getText().toString());
        if(title.trim().isEmpty()||u.trim().isEmpty()||qty<(original==null?1:0)||qty>100_000)throw new IllegalArgumentException("Укажите название, единицу и количество: новая упаковка от 1, существующая от 0, максимум 100000");
        Map<String,String> fields=new HashMap<>();fields.put("name",title);fields.put("unit",u);fields.put("expiry",date.toString());fields.put("photo",photo);
        if(original==null)fields.put("initialQuantity",Integer.toString(qty));
        save.setEnabled(false);io.execute(()->{try{
            if(original!=null){
                if(title.equals(original.name))fields.remove("name");
                if(u.equals(original.unit))fields.remove("unit");
                if(date.equals(original.printedExpiry))fields.remove("expiry");
                if(photo.equals(original.photo))fields.remove("photo");
            }
            if(!fields.isEmpty())store.change(product,fields);
            if(original!=null){int delta=qty-original.quantity;if(delta!=0)store.change(product,Map.of("delta",Integer.toString(delta)));}
            deleteDraft();runOnUiThread(()->{setResult(RESULT_OK);finish();});
        }catch(Exception e){runOnUiThread(()->{save.setEnabled(true);Ui.error(this,e);});}});
    }catch(Exception e){Ui.error(this,e);}}
    @Override protected void onSaveInstanceState(Bundle s){super.onSaveInstanceState(s);if(name!=null){s.putString("product",product);s.putString("name",name.getText().toString());s.putString("count",count.getText().toString());s.putString("unit",unit.getText().toString());s.putString("expiry",expiry.getText().toString());s.putString("made",made.getText().toString());s.putString("shelf",shelf.getText().toString());if(cameraUri!=null)s.putString("camera",cameraUri.toString());}}
    @Override protected void onDestroy(){io.shutdown();super.onDestroy();}
}
