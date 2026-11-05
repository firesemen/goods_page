package ru.fridgedays.app;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.UUID;

/** Only private, random JPEG captures can be shared, by a temporary URI grant. */
public final class CaptureProvider extends ContentProvider {
    static File file(Context c,Uri uri)throws FileNotFoundException{
        if(uri==null||!"content".equals(uri.getScheme())||!(c.getPackageName()+".capture").equals(uri.getAuthority())||uri.getPathSegments().size()!=2||!uri.getPathSegments().get(0).equals("capture"))throw new FileNotFoundException("Некорректный снимок");
        String name=uri.getLastPathSegment();if(name==null||!name.matches("[0-9a-f-]{36}\\.jpg"))throw new FileNotFoundException("Некорректное имя снимка");
        try{UUID.fromString(name.substring(0,36));}catch(IllegalArgumentException e){throw new FileNotFoundException("Некорректный снимок");}
        return new File(new File(c.getCacheDir(),"capture"),name);
    }
    static Uri create(Context c)throws IOException{
        Uri uri=new Uri.Builder().scheme("content").authority(c.getPackageName()+".capture").appendPath("capture").appendPath(UUID.randomUUID()+".jpg").build();File f=file(c,uri);
        if(!f.getParentFile().isDirectory()&&!f.getParentFile().mkdirs())throw new IOException("Не удалось подготовить папку камеры");if(!f.createNewFile())throw new IOException("Не удалось подготовить снимок");return uri;
    }
    static boolean ready(Context c,Uri uri){try{return file(c,uri).length()>0;}catch(IOException e){return false;}}
    static void remove(Context c,Uri uri){try{file(c,uri).delete();}catch(IOException ignored){}}
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){return "image/jpeg";}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        int flags=switch(mode){case "r"->ParcelFileDescriptor.MODE_READ_ONLY;case "w","wt"->ParcelFileDescriptor.MODE_WRITE_ONLY|ParcelFileDescriptor.MODE_TRUNCATE;case "rw"->ParcelFileDescriptor.MODE_READ_WRITE;case "rwt"->ParcelFileDescriptor.MODE_READ_WRITE|ParcelFileDescriptor.MODE_TRUNCATE;default->throw new FileNotFoundException("Неподдерживаемый режим");};
        File f=file(getContext(),uri);if(!f.isFile())throw new FileNotFoundException("Снимок недоступен");return ParcelFileDescriptor.open(f,flags);
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){try{File f=file(getContext(),uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns);Object[] values=new Object[columns.length];for(int n=0;n<columns.length;n++)values[n]=columns[n].equals(OpenableColumns.DISPLAY_NAME)?f.getName():columns[n].equals(OpenableColumns.SIZE)?f.length():null;cursor.addRow(values);return cursor;}catch(IOException e){return null;}}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
