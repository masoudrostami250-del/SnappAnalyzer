package ir.offline.tehran;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.ViewGroup;
import android.widget.*;
import android.graphics.Color;
import org.mapsforge.core.graphics.Paint;
import org.mapsforge.core.graphics.Style;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.layer.overlay.Polyline;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.rendertheme.InternalRenderTheme;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements LocationListener {
    private MapView mapView;
    private TileRendererLayer mapLayer;
    private Polyline routeLine;
    private OfflineRouter router;
    private LocationManager locationManager;
    private Location currentLocation;
    private TextToSpeech tts;
    private EditText destinationInput;
    private LinearLayout results;
    private TextView status;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private OfflineRouter.Place selectedPlace;
    private List<LatLong> activePath;
    private int routeProgress=0, lastAnnouncedTurn=-1;
    private boolean arrived=false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        AndroidGraphicFactory.createInstance(getApplication());
        locationManager=(LocationManager)getSystemService(LOCATION_SERVICE);
        tts=new TextToSpeech(this, result -> {
            if(result==TextToSpeech.SUCCESS) tts.setLanguage(new Locale("fa","IR"));
        });
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView title=new TextView(this);
        title.setText("مسیریاب آفلاین تهران و حومه");
        title.setTextSize(19); title.setPadding(12,10,12,4);
        root.addView(title);
        destinationInput=new EditText(this);
        destinationInput.setSingleLine(true);
        destinationInput.setHint("نام خیابان، محله یا مکان را بنویسید");
        root.addView(destinationInput);
        Button search=new Button(this); search.setText("جست‌وجوی آفلاین مقصد");
        root.addView(search);
        results=new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL);
        root.addView(results);
        Button go=new Button(this); go.setText("محاسبه مسیر از موقعیت من");
        root.addView(go);
        status=new TextView(this); status.setPadding(12,6,12,6);
        status.setText("در حال آماده‌سازی نقشه و اطلاعات مسیر...");
        root.addView(status);
        mapView=new MapView(this);
        mapView.setBuiltInZoomControls(true);
        mapView.getMapScaleBar().setVisible(true);
        root.addView(mapView,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));
        setContentView(root);
        loadBundledMap();
        worker.execute(() -> {
            try {
                OfflineRouter r=new OfflineRouter(this);
                runOnUiThread(() -> { router=r; status.setText("نقشه و جست‌وجوی آفلاین آماده است. GPS را روشن کنید."); });
            } catch(Exception e) {
                runOnUiThread(() -> status.setText("خطا در آماده‌سازی مسیرها: "+e.getMessage()));
            }
        });
        search.setOnClickListener(v -> searchPlaces());
        go.setOnClickListener(v -> calculateRoute());
        requestGps();
    }

    private void loadBundledMap() {
        try {
            File mapFile=new File(getFilesDir(),"iran-offline.map");
            if(!mapFile.exists() || mapFile.length()==0) {
                try(InputStream in=getAssets().open("iran.map"); FileOutputStream out=new FileOutputStream(mapFile)) {
                    byte[] b=new byte[1024*1024]; int n;
                    while((n=in.read(b))!=-1) out.write(b,0,n);
                }
            }
            TileCache cache=AndroidUtil.createTileCache(this,"offline-map-cache",
                    mapView.getModel().displayModel.getTileSize(),1f,
                    mapView.getModel().frameBufferModel.getOverdrawFactor());
            mapLayer=new TileRendererLayer(cache,new MapFile(mapFile),
                    mapView.getModel().mapViewPosition,AndroidGraphicFactory.INSTANCE);
            mapLayer.setXmlRenderTheme(InternalRenderTheme.DEFAULT);
            mapView.getLayerManager().getLayers().add(mapLayer);
            mapView.setCenter(new LatLong(35.6892,51.3890));
            mapView.setZoomLevel((byte)11);
        } catch(Exception e) {
            status.setText("خطا در بارگذاری نقشه: "+e.getMessage());
        }
    }

    private void searchPlaces() {
        if(router==null){status.setText("اطلاعات آفلاین هنوز آماده نشده است.");return;}
        String q=destinationInput.getText().toString().trim();
        if(q.length()<2){status.setText("حداقل دو حرف از نام مقصد را بنویسید.");return;}
        results.removeAllViews();
        worker.execute(() -> {
            List<OfflineRouter.Place> found=router.search(q,12);
            runOnUiThread(() -> {
                results.removeAllViews();
                if(found.isEmpty()){status.setText("مقصدی با این نام در نقشه آفلاین پیدا نشد.");return;}
                for(OfflineRouter.Place place:found){
                    Button item=new Button(this); item.setText(place.name);
                    item.setOnClickListener(v -> {
                        selectedPlace=place;
                        destinationInput.setText(place.name);
                        status.setText("مقصد انتخاب شد: "+place.name);
                    });
                    results.addView(item);
                }
                status.setText(found.size()+" نتیجه آفلاین پیدا شد؛ مقصد را انتخاب کنید.");
            });
        });
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
               checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }

    private void requestGps() {
        if(!hasLocationPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},77);
            return;
        }
        boolean gotLast=false;
        String[] providers={LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER,LocationManager.PASSIVE_PROVIDER};
        try {
            for(String provider:providers) {
                try {
                    if(!locationManager.isProviderEnabled(provider)) continue;
                    Location last=locationManager.getLastKnownLocation(provider);
                    if(last!=null && (!gotLast || isBetterLocation(last,currentLocation))) {
                        currentLocation=last; gotLast=true;
                    }
                    long minTime=provider.equals(LocationManager.GPS_PROVIDER)?1000:3000;
                    float minDistance=provider.equals(LocationManager.GPS_PROVIDER)?1:5;
                    locationManager.requestLocationUpdates(provider,minTime,minDistance,this);
                } catch(IllegalArgumentException | SecurityException ignored) {}
            }
            if(currentLocation!=null) {
                status.setText("موقعیت اولیه دریافت شد؛ دقت تقریبی "+(int)currentLocation.getAccuracy()+" متر. منتظر GPS دقیق‌تر...");
            } else {
                status.setText("در انتظار موقعیت گوشی؛ مکان‌یابی و GPS را روشن کنید و کنار پنجره یا فضای باز بمانید.");
            }
        } catch(SecurityException e) {
            status.setText("اجازه موقعیت مکانی داده نشده است.");
        }
    }

    private boolean isBetterLocation(Location candidate,Location current) {
        if(current==null) return true;
        long delta=candidate.getTime()-current.getTime();
        if(delta>12000) return true;
        if(delta< -12000) return false;
        return candidate.hasAccuracy() && (!current.hasAccuracy() || candidate.getAccuracy()<current.getAccuracy());
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==77 && hasLocationPermission()) requestGps();
        else if(requestCode==77) status.setText("برای مسیریابی، اجازه موقعیت مکانی را فعال کنید.");
    }

    private void calculateRoute() {
        if(selectedPlace==null){status.setText("ابتدا مقصد را جست‌وجو و انتخاب کنید.");return;}
        if(currentLocation==null){status.setText("موقعیت گوشی دریافت نشده؛ مکان‌یابی و GPS را روشن کنید و چند لحظه صبر کنید.");return;}
        if(currentLocation.hasAccuracy() && currentLocation.getAccuracy()>250) {
            status.setText("دقت موقعیت پایین است (حدود "+(int)currentLocation.getAccuracy()+" متر). چند لحظه در فضای باز صبر کنید تا GPS دقیق‌تر شود.");
            return;
        }
        final double lat=currentLocation.getLatitude(),lon=currentLocation.getLongitude();
        final OfflineRouter.Place destination=selectedPlace;
        status.setText("در حال محاسبه مسیر آفلاین...");
        worker.execute(() -> {
            List<LatLong> path=router.route(lat,lon,destination);
            runOnUiThread(() -> {
                if(path.isEmpty()){status.setText("مسیر جاده‌ای پیدا نشد. نقطه شروع یا مقصد را بررسی کنید.");return;}
                if(routeLine!=null) mapView.getLayerManager().getLayers().remove(routeLine);
                Paint paint=AndroidGraphicFactory.INSTANCE.createPaint();
                paint.setColor(Color.BLUE); paint.setStrokeWidth(8); paint.setStyle(Style.STROKE);
                routeLine=new Polyline(paint,AndroidGraphicFactory.INSTANCE);
                routeLine.addPoints(path);
                activePath=path; routeProgress=0; lastAnnouncedTurn=-1; arrived=false;
                mapView.getLayerManager().getLayers().add(routeLine);
                mapView.setCenter(path.get(0)); mapView.setZoomLevel((byte)14);
                status.setText("مسیر آفلاین تا "+destination.name+" محاسبه شد. دقت موقعیت شروع: "+(int)currentLocation.getAccuracy()+" متر.");
                if(tts!=null) tts.speak("مسیر آماده است. حرکت کنید.",TextToSpeech.QUEUE_FLUSH,null,"route-ready");
            });
        });
    }

    @Override public void onLocationChanged(Location location) {
        currentLocation=location;
        if(activePath==null || activePath.isEmpty() || arrived) return;
        int best=routeProgress; float bestDistance=Float.MAX_VALUE;
        int end=Math.min(activePath.size(),routeProgress+1500);
        for(int i=routeProgress;i<end;i++){
            LatLong p=activePath.get(i); float[] d=new float[1];
            Location.distanceBetween(location.getLatitude(),location.getLongitude(),p.latitude,p.longitude,d);
            if(d[0]<bestDistance){bestDistance=d[0];best=i;}
        }
        routeProgress=Math.max(routeProgress,best);
        LatLong finish=activePath.get(activePath.size()-1);
        float[] remainingToEnd=new float[1];
        Location.distanceBetween(location.getLatitude(),location.getLongitude(),finish.latitude,finish.longitude,remainingToEnd);
        if(remainingToEnd[0]<35){
            arrived=true;
            status.setText("به مقصد رسیدید.");
            if(tts!=null) tts.speak("به مقصد رسیدید.",TextToSpeech.QUEUE_FLUSH,null,"arrived");
            return;
        }
        mapView.setCenter(new LatLong(location.getLatitude(),location.getLongitude()));
        int turn=-1; double angle=0;
        for(int i=Math.max(routeProgress+2,2);i<activePath.size()-2;i++){
            double a=bearing(activePath.get(i-2),activePath.get(i));
            double b=bearing(activePath.get(i),activePath.get(i+2));
            double delta=((b-a+540)%360)-180;
            if(Math.abs(delta)>38){turn=i;angle=delta;break;}
        }
        if(turn>lastAnnouncedTurn && turn>=0){
            double meters=0;
            for(int i=routeProgress+1;i<=turn;i++){
                LatLong x=activePath.get(i-1),y=activePath.get(i);
                float[] d=new float[1]; Location.distanceBetween(x.latitude,x.longitude,y.latitude,y.longitude,d);
                meters+=d[0];
            }
            if(meters<500){
                lastAnnouncedTurn=turn;
                String direction=angle>0?"به راست":"به چپ";
                if(tts!=null) tts.speak("حدود "+Math.max(20,(int)meters)+" متر دیگر "+direction+" بپیچید.",TextToSpeech.QUEUE_FLUSH,null,"turn-"+turn);
            }
        }
    }

    private double bearing(LatLong a,LatLong b){
        double p1=Math.toRadians(a.latitude),p2=Math.toRadians(b.latitude);
        double dl=Math.toRadians(b.longitude-a.longitude);
        double y=Math.sin(dl)*Math.cos(p2);
        double x=Math.cos(p1)*Math.sin(p2)-Math.sin(p1)*Math.cos(p2)*Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y,x))+360)%360;
    }
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
    @Override public void onStatusChanged(String provider,int status,Bundle extras) {}

    @Override protected void onDestroy() {
        try { if(locationManager!=null) locationManager.removeUpdates(this); } catch(Exception ignored) {}
        if(tts!=null){tts.stop();tts.shutdown();}
        worker.shutdownNow();
        if(router!=null) router.close();
        if(mapView!=null) mapView.destroyAll();
        AndroidGraphicFactory.clearResourceMemoryCache();
        super.onDestroy();
    }
}