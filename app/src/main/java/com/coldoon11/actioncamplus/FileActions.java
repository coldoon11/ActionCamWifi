package com.coldoon11.actioncamplus;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
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
        int d = Math.round(activity.getResources().getDisplayMetrics().density);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(20 * d, 10 * d, 20 * d, 8 * d);

        TextView details = new TextView(activity);
        details.setText(file.details());
        details.setTextSize(15);
        details.setPadding(0, 0, 0, 12 * d);
        box.addView(details, new LinearLayout.LayoutParams(-1, -2));

        Button primary = actionButton(activity,
                file.isImage() ? "⬇ Скачать фото" : "▶ Смотреть без скачивания");
        box.addView(primary, fullWidth());

        Button download = null;
        if (!file.isImage()) {
            download = actionButton(activity, "⬇ Скачать как MP4");
            box.addView(download, fullWidth());
        }

        Button delete = actionButton(activity, "🗑 Удалить с карты камеры");
        box.addView(delete, fullWidth());

        Button cancel = actionButton(activity, "Отмена");
        box.addView(cancel, fullWidth());

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(file.displayName())
                .setView(box)
                .create();

        if (file.isImage()) {
            primary.setOnClickListener(v -> {
                dialog.dismiss();
                downloadOriginal.run();
            });
        } else {
            primary.setOnClickListener(v -> {
                dialog.dismiss();
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
            });

            download.setOnClickListener(v -> {
                dialog.dismiss();
                downloadMp4(activity, client, file, io, main, status);
            });
        }

        delete.setOnClickListener(v -> {
            dialog.dismiss();
            deleteFile.run();
        });

        cancel.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private static Button actionButton(Activity activity, String text) {
        Button button = new Button(activity);
        button.setText(text);
        button.setAllCaps(false);
        return button;
    }

    private static LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
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
                                    + "\n\nФайл сохранён как MP4 и должен отображаться в обычной галерее Android.")
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
