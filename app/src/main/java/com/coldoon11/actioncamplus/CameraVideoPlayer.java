package com.coldoon11.actioncamplus;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.os.Handler;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CameraVideoPlayer {
    private CameraVideoPlayer() {}

    public interface StatusSink {
        void setStatus(String text);
    }

    public static void show(
            Activity activity,
            GeneralPlusClient client,
            GeneralPlusClient.CameraFile file,
            ExecutorService io,
            Handler main,
            StatusSink status,
            Runnable downloadMp4,
            Runnable deleteFile
    ) {
        int density = Math.round(activity.getResources().getDisplayMetrics().density);
        AtomicBoolean closed = new AtomicBoolean(false);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(8 * density, 8 * density, 8 * density, 8 * density);

        VLCVideoLayout videoLayout = new VLCVideoLayout(activity);
        box.addView(videoLayout, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 280 * density));

        TextView note = new TextView(activity);
        note.setText("Подключаю поток камеры…");
        note.setPadding(4 * density, 8 * density, 4 * density, 4 * density);
        box.addView(note);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button download = new Button(activity);
        download.setText("Скачать MP4");
        download.setAllCaps(false);

        Button delete = new Button(activity);
        delete.setText("Удалить");
        delete.setAllCaps(false);

        actions.addView(download, new LinearLayout.LayoutParams(0, -2, 1f));
        actions.addView(delete, new LinearLayout.LayoutParams(0, -2, 1f));
        box.addView(actions);

        ArrayList<String> options = new ArrayList<>();
        options.add("--network-caching=120");
        options.add("--clock-jitter=0");
        options.add("--clock-synchro=0");
        options.add("--no-drop-late-frames");
        options.add("--no-skip-frames");

        LibVLC libVLC = new LibVLC(activity, options);
        MediaPlayer player = new MediaPlayer(libVLC);
        player.attachViews(videoLayout, null, false, false);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(file.displayName())
                .setView(box)
                .setNegativeButton("Закрыть", null)
                .create();

        player.setEventListener(event -> {
            if (closed.get()) return;
            if (event.type == MediaPlayer.Event.Playing) {
                main.post(() -> {
                    note.setText("▶ Воспроизведение прямо с камеры");
                    status.setStatus("Видео воспроизводится прямо с камеры.");
                });
            } else if (event.type == MediaPlayer.Event.EncounteredError) {
                main.post(() -> {
                    note.setText("Поток камеры не открылся");
                    status.setStatus("Ошибка видеопотока. Тип: "
                            + (client.isRtspSupported() ? "RTSP" : "HTTP")
                            + ". Попробуй закрыть и открыть ролик ещё раз.");
                });
            }
        });

        download.setOnClickListener(v -> {
            dialog.dismiss();
            downloadMp4.run();
        });

        delete.setOnClickListener(v -> {
            dialog.dismiss();
            deleteFile.run();
        });

        dialog.setOnShowListener(d -> {
            status.setStatus("Переключаю камеру в режим просмотра " + file.displayName() + "…");
            io.execute(() -> {
                try {
                    client.setPlaybackMode();
                    client.restartStreaming();
                    String url = client.playbackStreamUrl();

                    main.post(() -> {
                        if (closed.get()) return;
                        note.setText("Поток: " + (client.isRtspSupported() ? "RTSP" : "HTTP"));

                        Media media = new Media(libVLC, Uri.parse(url));
                        media.setHWDecoderEnabled(true, false);
                        media.addOption(":network-caching=120");
                        media.addOption(":live-caching=120");
                        media.addOption(":file-caching=120");
                        player.setMedia(media);
                        media.release();
                        player.play();

                        main.postDelayed(() -> {
                            if (closed.get()) return;
                            io.execute(() -> {
                                try {
                                    client.startPlayback(file);
                                } catch (Throwable t) {
                                    main.post(() -> {
                                        note.setText("Камера отклонила запуск ролика");
                                        status.setStatus("Playback: " + friendly(t));
                                    });
                                }
                            });
                        }, 300);
                    });
                } catch (Throwable t) {
                    main.post(() -> {
                        note.setText("Не удалось подготовить поток");
                        status.setStatus("Playback: " + friendly(t));
                    });
                }
            });
        });

        dialog.setOnDismissListener(d -> {
            closed.set(true);
            try { player.stop(); } catch (Throwable ignored) {}
            try { player.detachViews(); } catch (Throwable ignored) {}
            try { player.release(); } catch (Throwable ignored) {}
            try { libVLC.release(); } catch (Throwable ignored) {}

            io.execute(() -> {
                try {
                    client.stopPlayback(file);
                } catch (Throwable ignored) {
                }
            });
        });

        dialog.show();
    }

    private static String friendly(Throwable t) {
        String message = t.getMessage();
        return message == null || message.trim().isEmpty()
                ? t.getClass().getSimpleName() : message;
    }
}
