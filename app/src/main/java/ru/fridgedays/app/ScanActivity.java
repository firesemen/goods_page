package ru.fridgedays.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("deprecation")
public final class ScanActivity extends Activity implements SurfaceHolder.Callback {
    private SurfaceView surface;private Camera camera;private boolean surfaceReady;
    private final AtomicBoolean decoding=new AtomicBoolean(),done=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle b){super.onCreate(b);LinearLayout root=Ui.column(this);Ui.inset(this,root);root.addView(Ui.title(this,"Приглашение семьи"));root.addView(Ui.text(this,"Наведите камеру на QR в приложении второго телефона",16,Ui.MUTED));surface=new SurfaceView(this);root.addView(surface,new LinearLayout.LayoutParams(-1,0,1));root.addView(Ui.button(this,"Отмена",this::finish));setContentView(root);surface.getHolder().addCallback(this);if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.CAMERA},1);}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==1){if(g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)open();else{Toast.makeText(this,"Можно вставить ссылку приглашения вручную",Toast.LENGTH_LONG).show();finish();}}}
    @Override protected void onResume(){super.onResume();open();}
    @Override protected void onPause(){release();super.onPause();}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
    public void surfaceCreated(SurfaceHolder h){surfaceReady=true;open();}public void surfaceChanged(SurfaceHolder h,int f,int w,int l){}public void surfaceDestroyed(SurfaceHolder h){surfaceReady=false;release();}
    private void release(){if(camera!=null){camera.setPreviewCallback(null);camera.stopPreview();camera.release();camera=null;}}
    private void open(){if(!surfaceReady||camera!=null||checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
        try{camera=Camera.open();Camera.Parameters params=camera.getParameters();List<Camera.Size> sizes=params.getSupportedPreviewSizes();Camera.Size chosen=sizes.stream().min(Comparator.comparingInt(s->Math.abs(s.width*s.height-640*480))).orElseThrow();params.setPreviewSize(chosen.width,chosen.height);if(params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);camera.setParameters(params);
            Camera.CameraInfo info=new Camera.CameraInfo();Camera.getCameraInfo(0,info);int degrees=getWindowManager().getDefaultDisplay().getRotation()*90;camera.setDisplayOrientation((info.orientation-degrees+360)%360);camera.setPreviewDisplay(surface.getHolder());
            camera.setPreviewCallback((bytes,cam)->{if(done.get()||!decoding.compareAndSet(false,true))return;byte[] image=bytes.clone();try{worker.execute(()->{
                try{MultiFormatReader reader=new MultiFormatReader();reader.setHints(Map.of(DecodeHintType.POSSIBLE_FORMATS,List.of(BarcodeFormat.QR_CODE),DecodeHintType.TRY_HARDER,Boolean.TRUE));LuminanceSource source=new PlanarYUVLuminanceSource(image,chosen.width,chosen.height,0,0,chosen.width,chosen.height,false);
                    for(int rotation=0;rotation<4;rotation++)try{Result result=reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source)));String link=result.getText();if(link.startsWith("fridgedays://join#")&&done.compareAndSet(false,true))runOnUiThread(()->{setResult(RESULT_OK,new Intent().putExtra("invitation",link));finish();});break;}catch(NotFoundException e){if(source.isRotateSupported())source=source.rotateCounterClockwise();else break;}finally{reader.reset();}
                }catch(Exception ignored){}finally{decoding.set(false);}
            });}catch(RejectedExecutionException e){decoding.set(false);}});camera.startPreview();
        }catch(Exception e){release();Ui.error(this,new IllegalStateException("Камера недоступна. Вставьте ссылку приглашения вручную."));}
    }
}
