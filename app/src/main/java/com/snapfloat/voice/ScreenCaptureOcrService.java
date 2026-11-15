package com.snapfloat.voice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.content.pm.ServiceInfo;


import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.text.TextRecognizer;

import java.nio.ByteBuffer;

public class ScreenCaptureOcrService extends Service {
    private static final String TAG = "SnappScreenOCR";
    private static final String CHANNEL_ID = "snapp_screen_ocr";
    private static final int NOTIFICATION_ID = 7711;
    public static final String ACTION_STOP = "com.snapfloat.voice.STOP_SCREEN_OCR";
    public static final String EXTRA_RESULT_CODE = "projection_result_code";
    public static final String EXTRA_RESULT_DATA = "projection_result_data";

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread workerThread;
    private Handler worker;
    private TextRecognizer recognizer;
    private long lastFrameAt = 0L;
    private int screenWidth;
    private int screenHeight;
    private int densityDpi;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        workerThread = new HandlerThread("snapp-ocr-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null || !intent.hasExtra(EXTRA_RESULT_DATA)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        try {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, resultData);
            if (projection == null) throw new IllegalStateException("MediaProjection is null");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    stopSelf();
                }
            }, worker);
            startCapture();
        } catch (Exception e) {
            Log.e(TAG, "Could not start screen capture", e);
            getSharedPreferences("voice_settings", MODE_PRIVATE).edit()
                    .putString("screen_ocr_status", "خطا در شروع خواندن تصویری: " + e.getMessage())
                    .apply();
            stopSelf();
        }
        return START_STICKY;
    }

    private void startCapture() {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        densityDpi = metrics.densityDpi;

        imageReader = ImageReader.newInstance(screenWidth, screenHeight,
                PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null) return;
                long now = System.currentTimeMillis();
                if (now - lastFrameAt < 800L) return;
                lastFrameAt = now;

                Image.Plane[] planes = image.getPlanes();
                if (planes == null || planes.length == 0) return;
                ByteBuffer buffer = planes[0].getBuffer();
                int pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                int rowPadding = rowStride - pixelStride * screenWidth;
                Bitmap padded = Bitmap.createBitmap(
                        screenWidth + rowPadding / pixelStride,
                        screenHeight, Bitmap.Config.ARGB_8888);
                buffer.rewind();
                padded.copyPixelsFromBuffer(buffer);
                Bitmap frame = Bitmap.createBitmap(padded, 0, 0, screenWidth, screenHeight);
                padded.recycle();

                InputImage input = InputImage.fromBitmap(frame, 0);
                recognizer.process(input)
                        .addOnSuccessListener(result -> saveRecognizedText(result))
                        .addOnFailureListener(error -> Log.w(TAG, "OCR frame failed", error))
                        .addOnCompleteListener(task -> {
                            try { frame.recycle(); } catch (Exception ignored) { }
                        });
            } catch (Exception e) {
                Log.w(TAG, "Could not process captured frame", e);
            } finally {
                if (image != null) {
                    try { image.close(); } catch (Exception ignored) { }
                }
            }
        }, worker);

        virtualDisplay = projection.createVirtualDisplay(
                "SnappScreenOCR", screenWidth, screenHeight, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, worker);
        getSharedPreferences("voice_settings", MODE_PRIVATE).edit()
                .putString("screen_ocr_status", "خواندن تصویری صفحه فعال است؛ تصویر ذخیره نمی‌شود.")
                .apply();
    }

    private void saveRecognizedText(Text result) {
        if (result == null) return;
        String text = result.getText();
        if (text == null || text.trim().isEmpty()) return;
        getSharedPreferences("voice_settings", MODE_PRIVATE).edit()
                .putString("screen_ocr_text", text)
                .putLong("screen_ocr_time", System.currentTimeMillis())
                .putString("screen_ocr_status", "آخرین تصویر صفحه خوانده شد.")
                .apply();
        Log.d(TAG, "OCR_TEXT=" + text);
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder.setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("خواندن تصویری اسنپ فعال است")
                .setContentText("برای توقف، برنامه تحلیل‌گر را باز کنید.")
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "خواندن تصویری اسنپ", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) { }
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Exception ignored) { }
            imageReader = null;
        }
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) { }
            projection = null;
        }
        if (recognizer != null) {
            try { recognizer.close(); } catch (Exception ignored) { }
            recognizer = null;
        }
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
