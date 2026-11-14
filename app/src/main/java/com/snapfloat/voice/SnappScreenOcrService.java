package com.snapfloat.voice;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.media.MediaPlayer;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.snapfloat.R;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.regex.*;

public class SnappScreenOcrService extends Service {
  public static final String EXTRA_CODE="resultCode", EXTRA_DATA="resultData";
  private static final String TAG="SnappScreenOCR";
  private MediaProjection projection;
  private VirtualDisplay display;
  private ImageReader reader;
  private HandlerThread thread;
  private Handler handler;
  private com.google.mlkit.vision.text.TextRecognizer recognizer;
  private MediaPlayer player;
  private boolean busy=false;
  private String lastKey="";
  private long lastProcess=0;
  private int width,height,density;

  @Override public void onCreate(){
    super.onCreate();
    createNotification();
    recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    thread=new HandlerThread("snapp-screen-ocr"); thread.start(); handler=new Handler(thread.getLooper());
  }
  private void createNotification(){
    String channel="snapp_ocr_capture";
    if(Build.VERSION.SDK_INT>=26){
      NotificationChannel c=new NotificationChannel(channel,"خواندن صفحه اسنپ",NotificationManager.IMPORTANCE_LOW);
      getSystemService(NotificationManager.class).createNotificationChannel(c);
    }
    Notification n=new Notification.Builder(this,channel)
      .setContentTitle("تحلیل تصویری اسنپ فعال است")
      .setContentText("برای توقف، برنامه تحلیل سفر را باز کنید.")
      .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build();
    if(Build.VERSION.SDK_INT>=29) startForeground(72,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    else startForeground(72,n);
  }
  @Override public int onStartCommand(Intent intent,int flags,int startId){
    if(intent==null || !intent.hasExtra(EXTRA_DATA)){ stopSelf(); return START_NOT_STICKY; }
    int code=intent.getIntExtra(EXTRA_CODE,Activity.RESULT_CANCELED);
    Intent data=(Intent)intent.getParcelableExtra(EXTRA_DATA);
    if(data==null || code!=Activity.RESULT_OK){ stopSelf(); return START_NOT_STICKY; }
    try{
      MediaProjectionManager mgr=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
      projection=mgr.getMediaProjection(code,data);
      DisplayMetrics dm=new DisplayMetrics();
      WindowManager wm=(WindowManager)getSystemService(WINDOW_SERVICE);
      if(Build.VERSION.SDK_INT>=30){ android.view.Display d=wm.getDefaultDisplay(); d.getRealMetrics(dm); }
      else wm.getDefaultDisplay().getRealMetrics(dm);
      width=dm.widthPixels; height=dm.heightPixels; density=dm.densityDpi;
      reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2);
      reader.setOnImageAvailableListener(r->processLatest(r),handler);
      display=projection.createVirtualDisplay("SnappOCR",width,height,density,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,handler);
      projection.registerCallback(new MediaProjection.Callback(){@Override public void onStop(){stopSelf();}},handler);
      getSharedPreferences("voice_diagnostics",MODE_PRIVATE).edit().putBoolean("ocr_running",true).apply();
    }catch(Exception e){ Log.e(TAG,"Projection start failed",e); stopSelf(); }
    return START_NOT_STICKY;
  }
  private void processLatest(ImageReader r){
    Image image=null;
    try{
      image=r.acquireLatestImage();
      if(image==null || busy || System.currentTimeMillis()-lastProcess<1100) return;
      busy=true; lastProcess=System.currentTimeMillis();
      Image.Plane p=image.getPlanes()[0]; ByteBuffer b=p.getBuffer();
      int pixel=p.getPixelStride(), row=p.getRowStride();
      int paddedWidth=row/pixel;
      Bitmap padded=Bitmap.createBitmap(paddedWidth,height,Bitmap.Config.ARGB_8888);
      padded.copyPixelsFromBuffer(b);
      Bitmap bitmap=Bitmap.createBitmap(padded,0,0,width,height); padded.recycle();
      image.close(); image=null;
      InputImage input=InputImage.fromBitmap(bitmap,0);
      recognizer.process(input).addOnSuccessListener(result->{
        try{ analyzeOcr(result); }finally{bitmap.recycle(); busy=false;}
      }).addOnFailureListener(e->{Log.w(TAG,"OCR failed",e); bitmap.recycle(); busy=false;});
    }catch(Exception e){ if(image!=null) image.close(); busy=false; Log.e(TAG,"Frame failed",e); }
  }
  private void analyzeOcr(Text result){
    StringBuilder all=new StringBuilder();
    for(Text.TextBlock block:result.getTextBlocks()) for(Text.Line line:block.getLines()) all.append(' ').append(line.getText());
    String raw=digits(all.toString());
    Long fare=findFare(raw); ArrayList<Double> ds=findDistances(raw);
    getSharedPreferences("voice_diagnostics",MODE_PRIVATE).edit().putString("last_ocr_text",raw).apply();
    if(fare==null || ds.size()<2) return;
    double pickup=ds.get(0), destination=ds.get(1);
    if(pickup<=0 || destination<=0) return;
    boolean go=pickup<=2.0 && fare/destination>=17000.0;
    String key=fare+"|"+Math.round(pickup*1000)+"|"+Math.round(destination*1000);
    if(key.equals(lastKey)) return;
    lastKey=key;
    Log.i(TAG,"OCR_RESULT="+(go?"برو":"نرو")+" pickup="+pickup+" destination="+destination+" fare="+fare);
    play(go);
  }
  private String digits(String s){return s.replace('۰','0').replace('۱','1').replace('۲','2').replace('۳','3').replace('۴','4').replace('۵','5').replace('۶','6').replace('۷','7').replace('۸','8').replace('۹','9').replace('٠','0').replace('١','1').replace('٢','2').replace('٣','3').replace('٤','4').replace('٥','5').replace('٦','6').replace('٧','7').replace('٨','8').replace('٩','9').replace('٫','.').replace('٬',',');}
  private ArrayList<Double> findDistances(String s){
    ArrayList<Double> out=new ArrayList<>();
    Matcher m=Pattern.compile("([0-9]+(?:[.,][0-9]+)?)\\s*(km|k\\s*m|کیلومتر|کيلومتر)",Pattern.CASE_INSENSITIVE).matcher(s);
    while(m.find()) try{double d=Double.parseDouble(m.group(1).replace(',','.')); if(d>0&&d<=100)out.add(d);}catch(Exception ignored){}
    return out;
  }
  private Long findFare(String s){
    ArrayList<Long> vals=new ArrayList<>();
    Matcher m=Pattern.compile("([0-9][0-9,\\. ]{2,})\\s*(?:تومان|تومن|ریال)").matcher(s);
    while(m.find())try{long n=Long.parseLong(m.group(1).replaceAll("[,. ]",""));if(n>0&&n<1000000000L)vals.add(n);}catch(Exception ignored){}
    return vals.isEmpty()?null:Collections.max(vals);
  }
  private void play(boolean go){
    try{
      if(player!=null){player.release();player=null;}
      int choice=getSharedPreferences("voice_settings",MODE_PRIVATE).getInt("selected_voice",0);
      int id=choice==1?(go?R.raw.voice1_go:R.raw.voice1_stop):choice==2?(go?R.raw.voice2_go:R.raw.voice2_stop):choice==3?(go?R.raw.voice3_go:R.raw.voice3_stop):(go?R.raw.voice_go:R.raw.voice_stop);
      player=MediaPlayer.create(this,id); if(player!=null){player.setVolume(1,1);player.setOnCompletionListener(mp->{mp.release();if(player==mp)player=null;});player.start();}
    }catch(Exception e){Log.e(TAG,"Audio failed",e);}
  }
  @Override public void onDestroy(){
    getSharedPreferences("voice_diagnostics",MODE_PRIVATE).edit().putBoolean("ocr_running",false).apply();
    if(display!=null)display.release(); if(reader!=null)reader.close(); if(projection!=null)projection.stop();
    if(recognizer!=null)recognizer.close(); if(player!=null)player.release();
    if(thread!=null)thread.quitSafely(); super.onDestroy();
  }
  @Override public android.os.IBinder onBind(Intent i){return null;}
}
