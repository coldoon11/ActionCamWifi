package com.coldoon11.actioncamplus;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.Uri;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String CAMERA_SSID = "ActionCam_b40418003072";
    private static final String CAMERA_PASSWORD = "12345678";
    private static final int PERMISSION_REQUEST = 1001;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<GeneralPlusClient.CameraFile> files = new ArrayList<>();

    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Network cameraNetwork;
    private GeneralPlusClient client;

    private TextView status;
    private TextView cameraInfo;
    private ProgressBar progress;
    private ListView list;
    private ArrayAdapter<GeneralPlusClient.CameraFile> adapter;
    private Button connectButton;
    private Button refreshButton;
    private Button recordButton;
    private Button photoButton;
    private Button liveButton;

    private volatile boolean recording = false;
    private volatile boolean downloading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        buildUi();
    }

    private void buildUi() {
        int pad = dp(14);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("ActionCam+");
        title.setTextSize(26);
        title.setPadding(0, 0, 0, dp(4));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        cameraInfo = new TextView(this);
        cameraInfo.setText(CAMERA_SSID + "  •  Generalplus / GoPlus Cam");
        root.addView(cameraInfo, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setText("Готово. Включи Wi‑Fi на камере и нажми «Подключить».");
        status.setPadding(0, dp(8), 0, dp(10));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(8)));

        LinearLayout row1 = buttonRow();
        connectButton = button("Подключить");
        refreshButton = button("Обновить");
        row1.addView(connectButton, weighted());
        row1.addView(refreshButton, weighted());
        root.addView(row1);

        LinearLayout row2 = buttonRow();
        recordButton = button("Запись");
        photoButton = button("Фото");
        liveButton = button("Live");
        row2.addView(recordButton, weighted());
        row2.addView(photoButton, weighted());
        row2.addView(liveButton, weighted());
        root.addView(row2);

        TextView hint = new TextView(this);
        hint.setText("Нажми на файл — скачать. Долгое нажатие — удалить с карты камеры.");
        hint.setPadding(0, dp(10), 0, dp(6));
        root.addView(hint);

        list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, files);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);

        connectButton.setOnClickListener(v -> connectCamera());
        refreshButton.setOnClickListener(v -> refreshFiles());
        recordButton.setOnClickListener(v -> toggleRecord());
        photoButton.setOnClickListener(v -> capturePhoto());
        liveButton.setOnClickListener(v -> openLive());
        list.setOnItemClickListener((parent, view, position, id) -> downloadFile(files.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            confirmDelete(files.get(position));
            return true;
        });

        setControls(false);
    }

    private LinearLayout buttonRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(4), 0, dp(4));
        return row;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        return b;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setControls(boolean connected) {
        refreshButton.setEnabled(connected);
        recordButton.setEnabled(connected);
        photoButton.setEnabled(connected);
        liveButton.setEnabled(connected);
    }

    private boolean hasWifiPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void connectCamera() {
        if (!hasWifiPermission()) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, PERMISSION_REQUEST);
            } else {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_REQUEST);
            }
            return;
        }
        requestCameraNetwork();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == PERMISSION_REQUEST) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                requestCameraNetwork();
            } else {
                setStatus("Без разрешения Android не даст приложению подключиться к Wi‑Fi камеры.");
            }
        }
    }

    private void requestCameraNetwork() {
        releaseNetwork();
        setStatus("Подтверди подключение к " + CAMERA_SSID + " в системном окне Android…");
        connectButton.setEnabled(false);

        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                .setSsid(CAMERA_SSID)
                .setWpa2Passphrase(CAMERA_PASSWORD)
                .build();

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                cameraNetwork = network;
                connectivity.bindProcessToNetwork(network);
                String gateway = gatewayFrom(connectivity.getLinkProperties(network));
                setStatus("Wi‑Fi подключён. Ищу Generalplus TCP 8081…");
                io.execute(() -> connectProtocol(gateway));
            }

            @Override
            public void onUnavailable() {
                main.post(() -> {
                    connectButton.setEnabled(true);
                    setStatus("Android не подключил Wi‑Fi камеры. Проверь, что Wi‑Fi на камере включён.");
                });
            }

            @Override
            public void onLost(Network network) {
                if (cameraNetwork == network) {
                    cameraNetwork = null;
                    closeClient();
                    main.post(() -> {
                        setControls(false);
                        connectButton.setEnabled(true);
                        setStatus("Связь с камерой потеряна.");
                    });
                }
            }
        };

        try {
            connectivity.requestNetwork(request, networkCallback);
        } catch (Throwable t) {
            connectButton.setEnabled(true);
            setStatus("Ошибка запроса Wi‑Fi: " + friendly(t));
        }
    }

    private void connectProtocol(String gateway) {
        try {
            String host = GeneralPlusClient.firstReachableHost(Arrays.asList(
                    gateway, "192.168.25.1", "192.168.1.1", "192.168.0.1"
            ));
            if (host == null) throw new Exception("TCP 8081 камеры не найден");

            GeneralPlusClient c = new GeneralPlusClient(host);
            c.connect();
            closeClient();
            client = c;

            main.post(() -> {
                cameraInfo.setText(CAMERA_SSID + "  •  " + host + ":8081");
                connectButton.setEnabled(true);
                setControls(true);
                setStatus("Камера подключена. Читаю карту памяти…");
            });
            refreshFilesInternal();
        } catch (Throwable t) {
            main.post(() -> {
                connectButton.setEnabled(true);
                setControls(false);
                setStatus("Не удалось подключиться к камере: " + friendly(t));
            });
        }
    }

    private String gatewayFrom(LinkProperties props) {
        if (props == null) return "192.168.25.1";
        for (RouteInfo route : props.getRoutes()) {
            if (route.isDefaultRoute() && route.getGateway() instanceof Inet4Address) {
                return route.getGateway().getHostAddress();
            }
        }
        for (RouteInfo route : props.getRoutes()) {
            if (route.getGateway() instanceof Inet4Address && !route.getGateway().isAnyLocalAddress()) {
                return route.getGateway().getHostAddress();
            }
        }
        return "192.168.25.1";
    }

    private void refreshFiles() {
        if (client == null || downloading) return;
        setStatus("Обновляю список файлов…");
        io.execute(() -> {
            try {
                refreshFilesInternal();
            } catch (Throwable t) {
                main.post(() -> setStatus("Ошибка списка: " + friendly(t)));
            }
        });
    }

    private void refreshFilesInternal() throws Exception {
        GeneralPlusClient c = client;
        if (c == null) return;

        List<GeneralPlusClient.CameraFile> loaded = c.listFiles();
        Collections.sort(loaded, new Comparator<GeneralPlusClient.CameraFile>() {
            @Override
            public int compare(GeneralPlusClient.CameraFile a, GeneralPlusClient.CameraFile b) {
                long aa = ((((((long) a.year * 13 + a.month) * 32 + a.day) * 24 + a.hour) * 60 + a.minute) * 60 + a.second);
                long bb = ((((((long) b.year * 13 + b.month) * 32 + b.day) * 24 + b.hour) * 60 + b.minute) * 60 + b.second);
                return Long.compare(bb, aa);
            }
        });

        main.post(() -> {
            files.clear();
            files.addAll(loaded);
            adapter.notifyDataSetChanged();
            setStatus("На камере: " + files.size() + " файлов.");
        });
    }

    private void toggleRecord() {
        if (client == null || downloading) return;
        setStatus(recording ? "Останавливаю запись…" : "Запускаю запись…");
        io.execute(() -> {
            try {
                client.setRecordMode();
                client.toggleRecording();
                recording = !recording;
                main.post(() -> {
                    recordButton.setText(recording ? "Стоп запись" : "Запись");
                    setStatus(recording ? "Идёт запись." : "Запись остановлена.");
                });
            } catch (Throwable t) {
                main.post(() -> setStatus("Запись: " + friendly(t)));
            }
        });
    }

    private void capturePhoto() {
        if (client == null || downloading) return;
        setStatus("Делаю фото…");
        io.execute(() -> {
            try {
                client.setCaptureMode();
                client.restartStreaming();
                client.capturePhoto();
                main.post(() -> setStatus("Фото сделано."));
            } catch (Throwable t) {
                main.post(() -> setStatus("Фото: " + friendly(t)));
            }
        });
    }

    private void openLive() {
        if (client == null || downloading) return;
        setStatus("Запускаю live-view…");
        io.execute(() -> {
            try {
                client.setRecordMode();
                client.restartStreaming();
                Uri uri = Uri.parse(client.streamRtsp);
                main.post(() -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                        setStatus("Live-view открыт через видеоплеер телефона.");
                    } catch (Throwable t) {
                        setStatus("RTSP поток готов: " + uri + " — нужен плеер с RTSP (например VLC).");
                    }
                });
            } catch (Throwable t) {
                main.post(() -> setStatus("Live-view: " + friendly(t)));
            }
        });
    }

    private void downloadFile(GeneralPlusClient.CameraFile file) {
        if (client == null || downloading) return;
        downloading = true;
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);
        setStatus("Скачиваю " + file.displayName() + "…");
        setControls(false);

        io.execute(() -> {
            Uri uri = null;
            try {
                Uri collection = file.isImage()
                        ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;

                ContentValues initial = new ContentValues();
                initial.put(MediaStore.MediaColumns.DISPLAY_NAME, file.displayName());
                initial.put(MediaStore.MediaColumns.MIME_TYPE, file.isImage() ? "image/jpeg" : "video/*");
                initial.put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/ActionCamPlus");
                initial.put(MediaStore.MediaColumns.IS_PENDING, 1);

                uri = getContentResolver().insert(collection, initial);
                if (uri == null) throw new Exception("MediaStore не создал файл");

                ByteArrayOutputStream sniff = new ByteArrayOutputStream(64);
                long[] lastUi = {0L};

                try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                    if (out == null) throw new Exception("Не удалось открыть файл для записи");
                    client.download(file, bytes -> {
                        if (sniff.size() < 64) {
                            int n = Math.min(64 - sniff.size(), bytes.length);
                            sniff.write(bytes, 0, n);
                        }
                        out.write(bytes);
                    }, (downloaded, total) -> {
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastUi[0] > 200 || (total > 0 && downloaded >= total)) {
                            lastUi[0] = now;
                            int percent = total > 0 ? (int) Math.min(100, downloaded * 100L / total) : 0;
                            main.post(() -> {
                                progress.setProgress(percent);
                                setStatus("Скачиваю " + file.displayName() + " • " + percent + "%");
                            });
                        }
                    });
                    out.flush();
                }

                MediaInfo media = detect(file, sniff.toByteArray());
                String oldName = file.displayName();
                int dot = oldName.lastIndexOf('.');
                String base = dot > 0 ? oldName.substring(0, dot) : oldName;
                String finalName = base + "." + media.extension;

                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.DISPLAY_NAME, finalName);
                done.put(MediaStore.MediaColumns.MIME_TYPE, media.mime);
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(uri, done, null, null);

                main.post(() -> {
                    progress.setProgress(100);
                    setStatus("Сохранено в DCIM/ActionCamPlus: " + finalName);
                });
            } catch (Throwable t) {
                if (uri != null) {
                    try { getContentResolver().delete(uri, null, null); } catch (Throwable ignored) {}
                }
                main.post(() -> setStatus("Ошибка скачивания: " + friendly(t)));
            } finally {
                downloading = false;
                main.post(() -> {
                    progress.setVisibility(View.GONE);
                    setControls(client != null);
                });
            }
        });
    }

    private MediaInfo detect(GeneralPlusClient.CameraFile file, byte[] bytes) {
        if (file.isImage() || (bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8)) {
            return new MediaInfo("jpg", "image/jpeg");
        }
        String ascii = new String(bytes, StandardCharsets.ISO_8859_1);
        if (bytes.length >= 12 && ascii.startsWith("RIFF") && "AVI ".equals(ascii.substring(8, 12))) {
            return new MediaInfo("avi", "video/x-msvideo");
        }
        if (bytes.length >= 12 && "ftyp".equals(ascii.substring(4, 8))) {
            String brand = ascii.substring(8, 12);
            return "qt  ".equals(brand)
                    ? new MediaInfo("mov", "video/quicktime")
                    : new MediaInfo("mp4", "video/mp4");
        }
        String ext = file.guessedExtension();
        return "avi".equals(ext)
                ? new MediaInfo("avi", "video/x-msvideo")
                : new MediaInfo("mov", "video/quicktime");
    }

    private void confirmDelete(GeneralPlusClient.CameraFile file) {
        if (client == null || downloading) return;
        new AlertDialog.Builder(this)
                .setTitle("Удалить с карты камеры?")
                .setMessage(file.displayName())
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (d, which) -> deleteFile(file))
                .show();
    }

    private void deleteFile(GeneralPlusClient.CameraFile file) {
        setStatus("Удаляю " + file.displayName() + "…");
        io.execute(() -> {
            try {
                client.delete(file);
                main.post(() -> setStatus("Удалено: " + file.displayName()));
                refreshFilesInternal();
            } catch (Throwable t) {
                main.post(() -> setStatus("Удаление: " + friendly(t)));
            }
        });
    }

    private void setStatus(String text) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            status.setText(text);
        } else {
            main.post(() -> status.setText(text));
        }
    }

    private String friendly(Throwable t) {
        String message = t.getMessage();
        return message == null || message.trim().isEmpty() ? t.getClass().getSimpleName() : message;
    }

    private void closeClient() {
        GeneralPlusClient c = client;
        client = null;
        if (c != null) c.disconnect();
    }

    private void releaseNetwork() {
        closeClient();
        if (networkCallback != null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); } catch (Throwable ignored) {}
        }
        networkCallback = null;
        cameraNetwork = null;
        try { connectivity.bindProcessToNetwork(null); } catch (Throwable ignored) {}
    }

    @Override
    protected void onDestroy() {
        releaseNetwork();
        io.shutdownNow();
        super.onDestroy();
    }

    private static final class MediaInfo {
        final String extension;
        final String mime;

        MediaInfo(String extension, String mime) {
            this.extension = extension;
            this.mime = mime;
        }
    }
}
