package ru.fridgedays.app;

import android.content.Context;
import android.graphics.*;
import com.googlecode.tesseract.android.TessBaseAPI;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import ru.fridgedays.core.*;

/** One serial OCR job. CPU is used only while the user requests recognition. */
final class OcrEngine {
    private volatile boolean cancelled;private TessBaseAPI active;
    synchronized void cancel(){cancelled=true;if(active!=null)active.stop();}
    private void check(){if(cancelled||Thread.currentThread().isInterrupted())throw new CancellationException();}
    private static String digest(File file)throws Exception{MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)hash.update(b,0,n);}StringBuilder hex=new StringBuilder();for(byte b:hash.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return hex.toString();}
    private File models(Context c)throws Exception{
        File root=new File(c.getFilesDir(),"ocr"),data=new File(root,"tessdata");if(!data.isDirectory()&&!data.mkdirs())throw new IOException("Нет места для словарей распознавания");
        String[][] models={{"rus","e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225"},{"eng","7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"}};
        for(String[] model:models){check();File target=new File(data,model[0]+".traineddata");if(target.isFile()&&digest(target).equals(model[1]))continue;
            File temp=new File(target+".tmp");try(InputStream in=c.getAssets().open("tessdata/"+target.getName());OutputStream out=new FileOutputStream(temp)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1){check();out.write(b,0,n);}}
            if(!digest(temp).equals(model[1])){temp.delete();throw new IOException("Повреждён словарь распознавания. Переустановите APK");}Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);
        }return root;
    }
    PhotoDraft recognize(Context context,List<File> images,Consumer<String> progress)throws Exception{
        check();progress.accept("Подготовка словарей…");File root=models(context);check();
        TessBaseAPI api=new TessBaseAPI(values->{if(cancelled)return;progress.accept("Чтение надписей · "+values.getPercent()+"%");});
        try{
            synchronized(this){check();active=api;}
            if(!api.init(root.getAbsolutePath(),"rus+eng",TessBaseAPI.OEM_LSTM_ONLY))throw new IOException("Не удалось запустить распознавание");
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);StringBuilder text=new StringBuilder();int n=0;
            for(File file:images){check();progress.accept("Снимок "+(++n)+" из "+images.size()+"…");Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath());if(bitmap==null)throw new IOException("Снимок недоступен. Выберите фото заново");
                try{api.setImage(bitmap);api.getHOCRText(0);check();String part=api.getUTF8Text();if(part!=null)text.append(part).append('\n');api.clear();}finally{bitmap.recycle();}
            }
            check();return LabelParser.parse(text.toString());
        }finally{synchronized(this){active=null;api.recycle();}}
    }
}
