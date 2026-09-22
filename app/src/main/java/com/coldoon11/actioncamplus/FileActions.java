package com.coldoon11.actioncamplus;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;

public final class FileActions {
    private FileActions() {}

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
            Runnable downloadOriginal,
            Runnable deleteFile
    ) {
        if (file.isImage()) {
            new AlertDialog.Builder(activity)
                    .setTitle(file.displayName())
                    .setMessage(file.details())
                    .setItems(new String[]{
                            "Скачать фото",
                            "Удалить с карты камеры",
                            "Отмена"
                    }, (dialog, which) -> {
                        if (which == 0) downloadOriginal.run();
                        if (which == 1) deleteFile.run();
                    })
                    .show();
            return;
        }

        new AlertDialog.Builder(activity)
                .setTitle(file.displayName())
                .setMessage(file.details())
                .setItems(new String[]{
                        "▶ Смотреть без скачивания",
                        "⬇ Скачать как MP4",
                        "🗑 Удалить с карты камеры",
                        "Отмена"
                }, (dialog, which) -> {
                    if (which == 0) {
                        CameraVideoPlayer.show(
                                activity,
                                client,
                                file,
                                io,
                                main,
                                status::setStatus,
                                () -> downloadMp4(activity, client, file, io, main, status),
                                deleteFile
                        );
                    } else if (which == 1) {
                        downloadMp4(activity, client, file, io, main, status);
                    } else if (which == 2) {
                        deleteFile.run();
                    }
                })
                .show();
    }

    private static void downloadMp4(
            Activity activity,
            GeneralPlusClient client,
            GeneralPlusClient.CameraFile file,
            ExecutorService io,
            Handler main,
            StatusSink status
    ) {
        int density = Math.round(activity.getResources().getDisplayMetrics().density);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(20 * density, 14 * density, 20 * density, 14 * density);

        TextView phase = new TextView(activity);
        phase.setText("Подготовка…");
        box.addView(phase);

        ProgressBar bar = new ProgressBar(
                activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, 8 * density);
        barLp.topMargin = 12 * density;
        box.addView(bar, barLp);

        AlertDialog progressDialog = new AlertDialog.Builder(activity)
                .setTitle("Скачать MP4")
                .setMessage(file.displayName())
                .setView(box)
                .setCancelable(false)
                .create();
        progressDialog.show();

        status.setStatus("Скачиваю " + file.displayName() + " и подготовлю MP4…");

        io.execute(() -> {
            try {
                String saved = VideoMp4Downloader.download(
                        activity,
                        client,
                        file,
                        (percent, currentPhase) -> main.post(() -> {
                            bar.setProgress(percent);
                            phase.setText(currentPhase + " • " + percent + "%");
                        })
                );

                main.post(() -> {
                    progressDialog.dismiss();
                    status.setStatus("Готово: " + saved
                            + " сохранён в DCIM/ActionCamPlus.");
                    new AlertDialog.Builder(activity)
                            .setTitle("MP4 сохранён")
                            .setMessage(saved
                                    + "\n\nЭто уже не AVI — файл должен отображаться в обычной галерее Android.")
                            .setPositiveButton("OK", null)
                            .show();
                });
            } catch (Throwable t) {
                main.post(() -> {
                    progressDialog.dismiss();
                    String message = friendly(t);
                    status.setStatus("Ошибка MP4: " + message);
                    new AlertDialog.Builder(activity)
                            .setTitle("Не удалось сохранить MP4")
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    private static String friendly(Throwable t) {
        String message = t.getMessage();
        return message == null || message.trim().isEmpty()
                ? t.getClass().getSimpleName() : message;
    }
}
