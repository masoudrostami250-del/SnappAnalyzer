package ir.offline.tehran;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.datastore.MapDataStore;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {
    private static final int PICK_MAP = 41;
    private MapView mapView;
    private TileRendererLayer mapLayer;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        AndroidGraphicFactory.createInstance(getApplication());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView info = new TextView(this);
        info.setText("مسیریاب آفلاین تهران و حومه\nبرای نمایش نقشه، فایل نقشه با پسوند .map را از بستهٔ نقشه انتخاب کنید. پس از بارگذاری، نقشه بدون اینترنت نمایش داده می‌شود.\nمحدودهٔ موردنظر: تهران، ورامین، پاکدشت، قرچک، شهریار و اسلامشهر.");
        info.setPadding(18,12,18,12);
        root.addView(info);
        Button choose = new Button(this);
        choose.setText("انتخاب و بارگذاری نقشهٔ آفلاین");
        choose.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, PICK_MAP);
        });
        root.addView(choose);
        mapView = new MapView(this);
        mapView.setBuiltInZoomControls(true);
        mapView.getMapScaleBar().setVisible(true);
        root.addView(mapView, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_MAP || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        try {
            File file = new File(getCacheDir(), "region.map");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(file)) {
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (mapLayer != null) {
                mapView.getLayerManager().getLayers().remove(mapLayer);
                mapLayer.onDestroy();
            }
            TileCache cache = AndroidUtil.createTileCache(this, "offline-map-cache",
                    mapView.getModel().displayModel.getTileSize(), 1f,
                    mapView.getModel().frameBufferModel.getOverdrawFactor());
            MapDataStore store = new MapFile(file);
            mapLayer = new TileRendererLayer(cache, store, mapView.getModel().mapViewPosition,
                    AndroidGraphicFactory.INSTANCE);
            mapLayer.setXmlRenderTheme(MapsforgeThemes.DEFAULT);
            mapView.getLayerManager().getLayers().add(mapLayer);
            mapView.setCenter(new LatLong(35.6892, 51.3890));
            mapView.setZoomLevel((byte) 11);
            Toast.makeText(this, "نقشه بارگذاری شد", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "خطا در خواندن نقشه: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onDestroy() {
        if (mapView != null) mapView.destroyAll();
        AndroidGraphicFactory.clearResourceMemoryCache();
        super.onDestroy();
    }
}