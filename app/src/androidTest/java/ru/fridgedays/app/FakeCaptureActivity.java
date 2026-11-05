package ru.fridgedays.app;
import android.app.Activity;
import android.os.Bundle;
import android.provider.MediaStore;
import android.net.Uri;
import android.graphics.*;
import java.io.*;

/** Test APK, separate UID: exercise real Android URI write grants, return no result Intent like system cameras. */
public final class FakeCaptureActivity extends Activity {
 @Override public void onCreate(Bundle b){super.onCreate(b);try{
  Uri output=getIntent().getParcelableExtra(MediaStore.EXTRA_OUTPUT);if(output==null||getIntent().getClipData()==null)throw new IOException("Missing output/ClipData");
  Bitmap bitmap=Bitmap.createBitmap(1600,1200,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.rgb(240,80,50));try(OutputStream stream=getContentResolver().openOutputStream(output,"w")){bitmap.compress(Bitmap.CompressFormat.JPEG,95,stream);}bitmap.recycle();
  setResult(RESULT_OK);finish();
 }catch(Exception e){setResult(RESULT_CANCELED);finish();}}
}
