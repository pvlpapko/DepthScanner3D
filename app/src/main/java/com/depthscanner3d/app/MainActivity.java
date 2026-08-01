package com.depthscanner3d.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.depthscanner3d.app.export.ScanArchiveExporter;
import com.depthscanner3d.app.render.BackgroundRenderer;
import com.depthscanner3d.app.render.PointCloudRenderer;
import com.depthscanner3d.app.scan.ScanEngine;
import com.depthscanner3d.app.scan.ScanSnapshot;
import com.depthscanner3d.app.util.DisplayRotationHelper;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class MainActivity extends Activity implements GLSurfaceView.Renderer {
    private static final int CAMERA_PERMISSION_REQUEST = 1001;
    private static final int CREATE_EXPORT_REQUEST = 1002;
    private static final int MIN_EXPORT_POINTS = 200;

    private GLSurfaceView surfaceView;
    private TextView statusText;
    private TextView statsText;
    private Button startButton;
    private Button exportButton;
    private Button clearButton;
    private ProgressBar progressBar;

    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
    private final PointCloudRenderer pointCloudRenderer = new PointCloudRenderer();
    private final ScanEngine scanEngine = new ScanEngine();
    private final ExecutorService exportExecutor = Executors.newSingleThreadExecutor();

    private DisplayRotationHelper displayRotationHelper;
    private Session session;
    private Anchor scanAnchor;
    private boolean installRequested;
    private boolean depthSupported;
    private boolean textureNamesSet;
    private volatile boolean startPending;
    private volatile boolean renderingReady;
    private volatile boolean fatalRendererError;
    private long lastUiUpdateNanos;
    private long lastRenderBufferUpdateNanos;
    private long lastRenderedVersion = Long.MIN_VALUE;
    private volatile String rendererStatus = "Подготовка ARCore…";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        surfaceView = findViewById(R.id.gl_surface);
        statusText = findViewById(R.id.status_text);
        statsText = findViewById(R.id.stats_text);
        startButton = findViewById(R.id.start_button);
        exportButton = findViewById(R.id.export_button);
        clearButton = findViewById(R.id.clear_button);
        progressBar = findViewById(R.id.progress_bar);

        displayRotationHelper = new DisplayRotationHelper(this);
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        surfaceView.setRenderer(this);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        startButton.setEnabled(false);
        startButton.setOnClickListener(view -> handleStartStop());
        exportButton.setOnClickListener(view -> beginExport());
        clearButton.setOnClickListener(view -> confirmClear());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!hasCameraPermission()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            return;
        }
        startArSession();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (session != null) {
            displayRotationHelper.onPause();
            surfaceView.onPause();
            session.pause();
        }
    }

    @Override
    protected void onDestroy() {
        stopAndDetachAnchor();
        exportExecutor.shutdownNow();
        if (session != null) {
            session.close();
            session = null;
        }
        super.onDestroy();
    }


    private void startArSession() {
        if (!hasCameraPermission() || !ensureSession()) {
            return;
        }
        try {
            session.resume();
            displayRotationHelper.onResume();
            surfaceView.onResume();
            rendererStatus = depthSupported
                    ? "Наведите камеру на объект и дождитесь отслеживания"
                    : "Устройство не поддерживает ARCore Depth API";
            startButton.setEnabled(depthSupported && renderingReady);
        } catch (CameraNotAvailableException e) {
            showFatalError("Камера недоступна. Закройте другие приложения камеры и перезапустите сканер.");
        }
    }

    private boolean ensureSession() {
        if (session != null) {
            return true;
        }
        try {
            ArCoreApk.InstallStatus installStatus = ArCoreApk.getInstance()
                    .requestInstall(this, !installRequested);
            if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true;
                rendererStatus = "Установите или обновите Google Play Services for AR";
                return false;
            }

            session = new Session(this);
            Config config = session.getConfig();
            depthSupported = session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY);
            if (depthSupported) {
                config.setDepthMode(Config.DepthMode.RAW_DEPTH_ONLY);
            }
            config.setFocusMode(Config.FocusMode.AUTO);
            config.setLightEstimationMode(Config.LightEstimationMode.DISABLED);
            config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
            session.configure(config);
            textureNamesSet = false;
            return true;
        } catch (UnavailableException | RuntimeException e) {
            showFatalError("ARCore не удалось запустить: " + safeMessage(e));
            return false;
        }
    }

    private void handleStartStop() {
        if (scanEngine.isScanning() || startPending) {
            stopScanUi();
            return;
        }
        if (scanEngine.getPointCount() > 0) {
            new AlertDialog.Builder(this)
                    .setTitle("Начать новый скан?")
                    .setMessage("Текущие точки будут удалены. Сначала экспортируйте их, если они нужны.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Начать заново", (dialog, which) -> requestNewScan())
                    .show();
        } else {
            requestNewScan();
        }
    }

    private void requestNewScan() {
        stopAndDetachAnchor();
        scanEngine.clear();
        startPending = true;
        startButton.setText(R.string.stop_scan);
        rendererStatus = "Инициализация точки отсчёта…";
        updateButtons();
    }

    private void stopScanUi() {
        startPending = false;
        scanEngine.stop();
        startButton.setText(R.string.start_scan);
        rendererStatus = scanEngine.getPointCount() > 0
                ? "Сканирование остановлено. Можно экспортировать модель."
                : "Сканирование остановлено";
        updateButtons();
    }

    private void confirmClear() {
        if (scanEngine.getPointCount() == 0) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Очистить скан?")
                .setMessage("Все накопленные 3D-точки будут удалены.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Очистить", (dialog, which) -> {
                    stopAndDetachAnchor();
                    scanEngine.clear();
                    lastRenderedVersion = Long.MIN_VALUE;
                    surfaceView.queueEvent(() -> pointCloudRenderer.update(null));
                    startButton.setText(R.string.start_scan);
                    rendererStatus = "Скан очищен";
                    updateButtons();
                })
                .show();
    }

    private void beginExport() {
        if (scanEngine.getPointCount() < MIN_EXPORT_POINTS) {
            Toast.makeText(this, "Слишком мало точек для экспорта", Toast.LENGTH_SHORT).show();
            return;
        }
        stopScanUi();
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, "DepthScan_" + timestamp + ".zip");
        startActivityForResult(intent, CREATE_EXPORT_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CREATE_EXPORT_REQUEST || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        Uri target = data.getData();
        ScanSnapshot snapshot = scanEngine.createExportSnapshot();
        setExportBusy(true);
        exportExecutor.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(target, "w")) {
                if (output == null) {
                    throw new IOException("Система не предоставила поток записи");
                }
                ScanArchiveExporter.ExportResult result = ScanArchiveExporter.export(output, snapshot);
                runOnUiThread(() -> {
                    setExportBusy(false);
                    rendererStatus = String.format(Locale.US,
                            "Экспорт готов: %,d точек, %,d треугольников",
                            result.pointCount(), result.meshTriangleCount());
                    Toast.makeText(this, "ZIP с моделью сохранён", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setExportBusy(false);
                    rendererStatus = "Ошибка экспорта";
                    new AlertDialog.Builder(this)
                            .setTitle("Не удалось экспортировать")
                            .setMessage(safeMessage(e))
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    private void setExportBusy(boolean busy) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        startButton.setEnabled(!busy && depthSupported && renderingReady);
        exportButton.setEnabled(!busy && scanEngine.getPointCount() >= MIN_EXPORT_POINTS);
        clearButton.setEnabled(!busy && scanEngine.getPointCount() > 0);
    }

    private void updateButtons() {
        runOnUiThread(() -> {
            boolean hasPoints = scanEngine.getPointCount() > 0;
            startButton.setEnabled(depthSupported && renderingReady && !fatalRendererError);
            exportButton.setEnabled(hasPoints && scanEngine.getPointCount() >= MIN_EXPORT_POINTS);
            clearButton.setEnabled(hasPoints);
        });
    }

    private void stopAndDetachAnchor() {
        startPending = false;
        scanEngine.stop();
        Anchor oldAnchor = scanAnchor;
        scanAnchor = null;
        if (oldAnchor != null) {
            oldAnchor.detach();
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        try {
            backgroundRenderer.createOnGlThread(this);
            pointCloudRenderer.createOnGlThread(this);
            textureNamesSet = false;
            renderingReady = true;
            lastRenderedVersion = Long.MIN_VALUE;
            runOnUiThread(() -> startButton.setEnabled(depthSupported));
        } catch (IOException | RuntimeException e) {
            fatalRendererError = true;
            renderingReady = false;
            runOnUiThread(() -> showFatalError("Ошибка OpenGL: " + safeMessage(e)));
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        displayRotationHelper.onSurfaceChanged(width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        Session activeSession = session;
        if (activeSession == null || fatalRendererError || !renderingReady) {
            return;
        }
        try {
            if (!textureNamesSet) {
                activeSession.setCameraTextureNames(new int[]{backgroundRenderer.getTextureId()});
                textureNamesSet = true;
            }
            displayRotationHelper.updateSessionIfNeeded(activeSession);
            Frame frame = activeSession.update();
            Camera camera = frame.getCamera();
            backgroundRenderer.draw(frame);

            if (camera.getTrackingState() == TrackingState.TRACKING) {
                if (startPending) {
                    Anchor oldAnchor = scanAnchor;
                    if (oldAnchor != null) {
                        oldAnchor.detach();
                    }
                    scanAnchor = activeSession.createAnchor(camera.getPose());
                    scanEngine.begin(scanAnchor);
                    startPending = false;
                    rendererStatus = "Сканирование: медленно обходите объект";
                }
                if (scanEngine.isScanning()) {
                    scanEngine.processFrame(frame, camera);
                }
                updateRenderBufferIfNeeded();
                Anchor anchor = scanEngine.getScanAnchor();
                if (anchor != null && anchor.getTrackingState() == TrackingState.TRACKING) {
                    pointCloudRenderer.draw(camera, anchor.getPose());
                }
            } else {
                rendererStatus = startPending || scanEngine.isScanning()
                        ? "Отслеживание потеряно — двигайте телефон медленнее и добавьте света"
                        : "Ожидание устойчивого отслеживания ARCore";
            }
            updateUiThrottled();
        } catch (CameraNotAvailableException e) {
            runOnUiThread(() -> showFatalError("Камера стала недоступна: " + safeMessage(e)));
        } catch (RuntimeException e) {
            rendererStatus = "Ошибка обработки кадра: " + safeMessage(e);
            updateUiThrottled();
        }
    }

    private void updateRenderBufferIfNeeded() {
        long now = System.nanoTime();
        long version = scanEngine.getRenderVersion();
        if (version == lastRenderedVersion
                || now - lastRenderBufferUpdateNanos < 350_000_000L) {
            return;
        }
        FloatBuffer buffer = scanEngine.createRenderBuffer();
        pointCloudRenderer.update(buffer);
        lastRenderedVersion = version;
        lastRenderBufferUpdateNanos = now;
    }

    private void updateUiThrottled() {
        long now = System.nanoTime();
        if (now - lastUiUpdateNanos < 250_000_000L) {
            return;
        }
        lastUiUpdateNanos = now;
        int points = scanEngine.getPointCount();
        int frames = scanEngine.getIntegratedDepthFrames();
        String status = rendererStatus;
        runOnUiThread(() -> {
            statusText.setText(status);
            statsText.setText(String.format(Locale.US,
                    "Точек: %,d · кадров глубины: %,d", points, frames));
            boolean hasPoints = points > 0;
            exportButton.setEnabled(hasPoints && points >= MIN_EXPORT_POINTS
                    && progressBar.getVisibility() != View.VISIBLE);
            clearButton.setEnabled(hasPoints && progressBar.getVisibility() != View.VISIBLE);
            startButton.setText(scanEngine.isScanning() || startPending
                    ? R.string.stop_scan : R.string.start_scan);
        });
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION_REQUEST) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startArSession();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Нужен доступ к камере")
                .setMessage("Без камеры приложение не может получать кадры и карту глубины.")
                .setNegativeButton("Закрыть", (dialog, which) -> finish())
                .setPositiveButton("Настройки", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .show();
    }

    private void showFatalError(String message) {
        fatalRendererError = true;
        startButton.setEnabled(false);
        exportButton.setEnabled(scanEngine.getPointCount() >= MIN_EXPORT_POINTS);
        rendererStatus = message;
        statusText.setText(message);
        new AlertDialog.Builder(this)
                .setTitle("Depth Scanner 3D")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName()
                : message;
    }
}
