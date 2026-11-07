package ir.offline.tehran;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.rendertheme.InternalRenderTheme;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {
    private MapView mapView;
    private TileRendererLayer mapLayer;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        AndroidGraphicFactory.createInstance(getApplication());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView info = new TextView(this);
        info.setText("نقشه آفلاین تهران و حومه\nتهران، ورامین، پاکدشت، قرچک، شهریار و اسلامشهر\nنقشه همراه برنامه نصب می‌شود و برای نمایش آن اینترنت لازم نیست.");
        info.setPadding(18, 14, 18, 14);
        root.addView(info);
        mapView = new MapView(this);
        mapView.setBuiltInZoomControls(true);
        mapView.getMapScaleBar().setVisible(true);
        root.addView(mapView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        loadBundledMap();
    }

    private void loadBundledMap() {
        try {
            File mapFile = new File(getFilesDir(), "iran-offline.map");
            if (!mapFile.exists() || mapFile.length() == 0) {
                try (InputStream in = getAssets().open("iran.map");
                     FileOutputStream out = new FileOutputStream(mapFile)) {
                    byte[] buffer = new byte[1024 * 1024];
                    int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    out.getFD().sync();
                }
            }
            TileCache cache = AndroidUtil.createTileCache(this, "offline-map-cache",
                    mapView.getModel().displayModel.getTileSize(), 1f,
                    mapView.getModel().frameBufferModel.getOverdrawFactor());
            mapLayer = new TileRendererLayer(cache, new MapFile(mapFile),
                    mapView.getModel().mapViewPosition, AndroidGraphicFactory.INSTANCE);
            mapLayer.setXmlRenderTheme(InternalRenderTheme.DEFAULT);
            mapView.getLayerManager().getLayers().add(mapLayer);
            mapView.setCenter(new LatLong(35.6892, 51.3890));
            mapView.setZoomLevel((byte) 11);
        } catch (Exception e) {
            Toast.makeText(this, "خطا در بارگذاری نقشه: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onDestroy() {
        if (mapView != null) mapView.destroyAll();
        AndroidGraphicFactory.clearResourceMemoryCache();
        super.onDestroy();
    }
}