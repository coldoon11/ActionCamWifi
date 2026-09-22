package com.coldoon11.actioncamplus;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.util.concurrent.ExecutorService;

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
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(8 * density, 8 * density, 8 * density, 8 * density);

        PlayerView playerView = new PlayerView(activity);
        box.addView(playerView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 260 * density));

        TextView note = new TextView(activity);
        note.setText("Видео идёт напрямую с SD-карты камеры по Wi‑Fi — без скачивания.");
        note.setPadding(4 * density, 8 * density, 4 * density, 4 * density);
        box.addView(note);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button download = new Button(activity);
        download.setText("Скачать MP4");
        Button delete = new Button(activity);
        delete.setText("Удалить");

        actions.addView(download, new LinearLayout.LayoutParams(0, -2, 1f));
        actions.addView(delete, new LinearLayout.LayoutParams(0, -2, 1f));
        box.addView(actions);

        ExoPlayer player = new ExoPlayer.Builder(activity).build();
        playerView.setPlayer(player);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(file.displayName())
                .setView(box)
                .setNegativeButton("Закрыть", null)
                .create();

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                status.setStatus("Плеер: " + error.getErrorCodeName()
                        + ". Закрой окно и попробуй ещё раз.");
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
            status.setStatus("Открываю " + file.displayName() + " с камеры…");
            try {
                player.setMediaItem(MediaItem.fromUri(client.streamRtsp));
                player.prepare();
                player.setPlayWhenReady(true);

                main.postDelayed(() -> io.execute(() -> {
                    try {
                        client.setPlaybackMode();
                        client.restartStreaming();
                        client.startPlayback(file);
                        main.post(() -> status.setStatus(
                                "Видео воспроизводится прямо с камеры."));
                    } catch (Throwable t) {
                        main.post(() -> status.setStatus(
                                "Камера не запустила видео: " + friendly(t)));
                    }
                }), 500);
            } catch (Throwable t) {
                status.setStatus("Плеер: " + friendly(t));
            }
        });

        dialog.setOnDismissListener(d -> {
            player.release();
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
