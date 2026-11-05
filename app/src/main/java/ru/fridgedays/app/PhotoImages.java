package ru.fridgedays.app;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.util.Base64;
import java.io.*;
import java.nio.file.Files;

/** High-resolution inputs stay in the private cache, separate from the small card image. */
final class PhotoImages {
    /** Delete only the dictionaries installed by 0.3.0; never touch inventory or its keys. */
    static void clearLegacyModels(Context c){
        File root=new File(c.getFilesDir(),"ocr"),data=new File(root,"tessdata");
        for(String language:new String[]{"rus","eng"})for(String suffix:new String[]{".traineddata",".traineddata.tmp"})new File(data,language+suffix).delete();
        data.delete();root.delete();
    }
    static File source(Context c,String product,boolean date){return new File(c.getCacheDir(),"ocr-"+product+(date?"-date.jpg":"-front.jpg"));}
    static Bitmap decode(Context c,Uri uri,int edge)throws IOException{
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.getContentResolver(),uri),(d,info,s)->{
            int w=info.getSize().getWidth(),h=info.getSize().getHeight();
            double scale=Math.min(1,Math.min((double)edge/Math.max(w,h),Math.sqrt(3_600_000d/((double)w*h))));
            d.setTargetSize(Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)));d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }
    static byte[] jpeg(Bitmap bitmap,int edge,int limit)throws IOException{
        Bitmap small=bitmap;float factor=Math.min(1f,(float)edge/Math.max(bitmap.getWidth(),bitmap.getHeight()));
        if(factor<1)small=Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(bitmap.getWidth()*factor)),Math.max(1,Math.round(bitmap.getHeight()*factor)),true);
        try{ByteArrayOutputStream out=new ByteArrayOutputStream();int quality=90;do{out.reset();small.compress(Bitmap.CompressFormat.JPEG,quality,out);quality-=10;}while(out.size()>limit&&quality>=30);
            if(out.size()>limit)throw new IOException("Снимок слишком сложный. Снимите только упаковку или маркировку крупным планом");return out.toByteArray();
        }finally{if(small!=bitmap)small.recycle();}
    }
    static String importImage(Context c,String product,boolean date,Uri uri)throws IOException{
        Bitmap image=decode(c,uri,2400);
        try{
            byte[] full=jpeg(image,2400,2_000_000);String thumbnail=date?null:Base64.encodeToString(jpeg(image,800,170_000),Base64.NO_WRAP);File target=source(c,product,date),temp=new File(target+".tmp");
            Files.write(temp.toPath(),full);Files.move(temp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return thumbnail;
        }finally{image.recycle();}
    }
    static String cloud(File file)throws IOException{
        Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath());if(bitmap==null)throw new IOException("Не удалось прочитать снимок");
        try{return Base64.encodeToString(jpeg(bitmap,1600,900_000),Base64.NO_WRAP);}finally{bitmap.recycle();}
    }
    static void clear(Context c,String product){for(boolean date:new boolean[]{false,true}){File f=source(c,product,date);f.delete();new File(f+".tmp").delete();}}
}
