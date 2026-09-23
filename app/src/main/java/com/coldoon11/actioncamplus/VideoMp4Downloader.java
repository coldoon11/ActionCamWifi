package com.coldoon11.actioncamplus;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class VideoMp4Downloader {
    private VideoMp4Downloader() {}

    public interface Progress {
        void onProgress(int percent, String phase);
    }

    public static String download(
            Context context,
            GeneralPlusClient client,
            GeneralPlusClient.CameraFile file,
            Progress progress
    ) throws Exception {
        File raw = new File(context.getCacheDir(),
                "actioncam_" + file.deviceIndex + "_" + System.currentTimeMillis() + ".raw");
        File mp4 = new File(context.getCacheDir(),
                "actioncam_" + file.deviceIndex + "_" + System.currentTimeMillis() + ".mp4");
        Uri outputUri = null;

        try {
            try (FileOutputStream out = new FileOutputStream(raw)) {
                client.download(file, out::write, (downloaded, total) -> {
                    int p = total > 0 ? (int) Math.min(100, downloaded * 100L / total) : 0;
                    progress.onProgress(p, "Скачивание с камеры");
                });
                out.flush();
            }

            String source = detectContainer(raw);
            if ("mp4".equals(source)) {
                copy(raw, mp4);
            } else {
                progress.onProgress(100,
                        "Конвертация " + source.toUpperCase(Locale.US) + " → MP4");
                convert(raw, mp4);
            }

            if (!mp4.exists() || mp4.length() < 1024) {
                throw new Exception("Получился пустой MP4");
            }

            String name = file.displayName();
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
            name += ".mp4";

            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/ActionCamPlus");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            outputUri = context.getContentResolver().insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (outputUri == null) throw new Exception("Android не создал файл MP4");

            try (OutputStream out = context.getContentResolver().openOutputStream(outputUri, "w");
                 BufferedInputStream in = new BufferedInputStream(new FileInputStream(mp4))) {
                if (out == null) throw new Exception("Не удалось записать MP4");
                byte[] buffer = new byte[128 * 1024];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, n);
                }
                out.flush();
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            context.getContentResolver().update(outputUri, done, null, null);
            outputUri = null;
            return name;
        } catch (Exception e) {
            if (outputUri != null) {
                try {
                    context.getContentResolver().delete(outputUri, null, null);
                } catch (Throwable ignored) {
                }
            }
            throw e;
        } finally {
            try { raw.delete(); } catch (Throwable ignored) {}
            try { mp4.delete(); } catch (Throwable ignored) {}
        }
    }

    private static void convert(File input, File output) throws Exception {
        // 1) Самый быстрый путь: перепаковать дорожки без потери качества.
        String remux = "-y -i " + q(input)
                + " -map 0:v:0? -map 0:a:0? -c copy -movflags +faststart " + q(output);
        FFmpegSession remuxSession = FFmpegKit.execute(remux);
        if (ReturnCode.isSuccess(remuxSession.getReturnCode())
                && output.exists() && output.length() > 1024) {
            return;
        }

        if (output.exists()) output.delete();

        // 2) Частый случай экшн-камер: H.264 совместим с MP4, а звук в AVI/MOV — нет.
        // Видео оставляем оригинальным, перекодируем только аудио в AAC.
        String audioFix = "-y -i " + q(input)
                + " -map 0:v:0? -map 0:a:0?"
                + " -c:v copy -c:a aac -b:a 160k"
                + " -movflags +faststart " + q(output);
        FFmpegSession audioSession = FFmpegKit.execute(audioFix);
        if (ReturnCode.isSuccess(audioSession.getReturnCode())
                && output.exists() && output.length() > 1024) {
            return;
        }

        if (output.exists()) output.delete();

        // 3) Совместимый запасной вариант, если исходный видеокодек нельзя положить в MP4.
        String transcode = "-y -i " + q(input)
                + " -map 0:v:0? -map 0:a:0?"
                + " -c:v mpeg4 -q:v 3 -pix_fmt yuv420p"
                + " -c:a aac -b:a 160k"
                + " -movflags +faststart " + q(output);
        FFmpegSession transcodeSession = FFmpegKit.execute(transcode);
        if (!ReturnCode.isSuccess(transcodeSession.getReturnCode())
                || !output.exists() || output.length() < 1024) {
            throw new Exception("FFmpeg не смог преобразовать видео в MP4 (код "
                    + transcodeSession.getReturnCode() + ")");
        }
    }

    private static String detectContainer(File file) throws Exception {
        byte[] head = new byte[64];
        int n;
        try (FileInputStream in = new FileInputStream(file)) {
            n = in.read(head);
        }
        if (n < 12) return "avi";

        String ascii = new String(head, 0, n, StandardCharsets.ISO_8859_1);
        if (ascii.startsWith("RIFF") && n >= 12 && "AVI ".equals(ascii.substring(8, 12))) {
            return "avi";
        }
        if (n >= 12 && "ftyp".equals(ascii.substring(4, 8))) {
            String brand = ascii.substring(8, 12);
            if ("qt  ".equals(brand)) return "mov";
            return "mp4";
        }
        return "avi";
    }

    private static void copy(File source, File target) throws Exception {
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(source));
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[128 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            out.flush();
        }
    }

    private static String q(File file) {
        return "'" + file.getAbsolutePath().replace("'", "'\\''") + "'";
    }
}
