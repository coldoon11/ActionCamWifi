package com.coldoon11.actioncamplus;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import javax.net.SocketFactory;

public final class GeneralPlusClient {
    public static final int PORT = 8081;

    private static final int TYPE_CMD = 0x0001;
    private static final int TYPE_ACK = 0x0002;
    private static final int TYPE_NAK = 0x0003;

    private static final int MODE_GENERAL = 0x00;
    private static final int MODE_RECORD = 0x01;
    private static final int MODE_CAPTURE = 0x02;
    private static final int MODE_PLAYBACK = 0x03;

    private static final int CMD_SET_MODE = 0x00;
    private static final int CMD_RESTART_STREAM = 0x04;
    private static final int CMD_AUTH = 0x05;

    private static final int RECORD_START_STOP = 0x00;
    private static final int CAPTURE = 0x00;

    private static final int PB_FILE_COUNT = 0x02;
    private static final int PB_NAME_LIST = 0x03;
    private static final int PB_THUMBNAIL = 0x04;
    private static final int PB_RAW_DATA = 0x05;
    private static final int PB_DELETE = 0x08;

    private static final int[] AUTH_LUT = {
            0x0E, 0x47, 0xDB, 0x46, 0x46, 0x8D, 0x38, 0xE5,
            0xFC, 0x52, 0x7A, 0xDE, 0x6F, 0xC5, 0x05, 0xE6
    };

    public final String host;
    public final String streamRtsp;

    private final SocketFactory socketFactory;
    private Socket socket;
    private BufferedInputStream input;
    private BufferedOutputStream output;

    public GeneralPlusClient(String host, SocketFactory socketFactory) {
        this.host = host;
        this.socketFactory = socketFactory != null ? socketFactory : SocketFactory.getDefault();
        this.streamRtsp = "rtsp://" + host + ":8080/?action=stream";
    }

    public static String firstReachableHost(SocketFactory socketFactory, List<String> hosts) {
        SocketFactory factory = socketFactory != null ? socketFactory : SocketFactory.getDefault();
        LinkedHashSet<String> unique = new LinkedHashSet<>(hosts);
        for (String host : unique) {
            if (host == null || host.trim().isEmpty()) continue;
            try (Socket probe = factory.createSocket()) {
                probe.connect(new InetSocketAddress(host, PORT), 1000);
                return host;
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    public void connect() throws IOException {
        disconnect();
        Socket s = socketFactory.createSocket();
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        s.connect(new InetSocketAddress(host, PORT), 4000);
        s.setSoTimeout(10000);
        socket = s;
        input = new BufferedInputStream(s.getInputStream(), 64 * 1024);
        output = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
        authenticate();
    }

    public void disconnect() {
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
        }
        socket = null;
        input = null;
        output = null;
    }

    public void setRecordMode() throws IOException {
        transact(MODE_GENERAL, CMD_SET_MODE, new byte[]{0x00});
    }

    public void setCaptureMode() throws IOException {
        transact(MODE_GENERAL, CMD_SET_MODE, new byte[]{0x01});
    }

    public void setPlaybackMode() throws IOException {
        transact(MODE_GENERAL, CMD_SET_MODE, new byte[]{0x02});
    }

    public void toggleRecording() throws IOException {
        transact(MODE_RECORD, RECORD_START_STOP, new byte[0]);
    }

    public void capturePhoto() throws IOException {
        transact(MODE_CAPTURE, CAPTURE, new byte[0]);
    }

    public void restartStreaming() throws IOException {
        transact(MODE_GENERAL, CMD_RESTART_STREAM, new byte[0]);
    }

    public List<CameraFile> listFiles() throws IOException {
        ensureConnected();
        setPlaybackMode();

        Packet countPacket = transact(MODE_PLAYBACK, PB_FILE_COUNT, new byte[0]);
        if (countPacket.payload.length < 2) return new ArrayList<>();
        int count = u16(countPacket.payload, 0);
        if (count <= 0) return new ArrayList<>();

        ArrayList<CameraFile> result = new ArrayList<>();
        boolean first = true;
        int lastDeviceIndex = 0;
        int guard = 0;

        while (result.size() < count && guard++ < count + 20) {
            byte[] request = new byte[] {
                    (byte) (first ? 1 : 0),
                    (byte) (lastDeviceIndex & 0xff),
                    (byte) ((lastDeviceIndex >>> 8) & 0xff)
            };
            Packet packet = transact(MODE_PLAYBACK, PB_NAME_LIST, request);
            if (packet.payload.length == 0) break;

            int batchCount = packet.payload[0] & 0xff;
            if (batchCount <= 0) break;

            int bytes = packet.payload.length - 1;
            if (bytes <= 0 || bytes % batchCount != 0) break;
            int attrSize = bytes / batchCount;
            if (attrSize < 13) break;

            for (int i = 0; i < batchCount && result.size() < count; i++) {
                int off = 1 + i * attrSize;
                if (off + 13 > packet.payload.length) break;

                byte[] p = packet.payload;
                CameraFile f = new CameraFile(
                        (char) (p[off] & 0xff),
                        u16(p, off + 1),
                        2000 + (p[off + 3] & 0xff),
                        p[off + 4] & 0xff,
                        p[off + 5] & 0xff,
                        p[off + 6] & 0xff,
                        p[off + 7] & 0xff,
                        p[off + 8] & 0xff,
                        u32(p, off + 9)
                );
                result.add(f);
                lastDeviceIndex = f.deviceIndex;
            }
            first = false;
        }
        return result;
    }

    public interface ChunkConsumer {
        void accept(byte[] bytes) throws IOException;
    }

    public interface ProgressConsumer {
        void accept(long downloadedBytes, long estimatedTotalBytes);
    }

    public void download(CameraFile file, ChunkConsumer chunks, ProgressConsumer progress) throws IOException {
        ensureConnected();
        setPlaybackMode();
        sendCommand(MODE_PLAYBACK, PB_RAW_DATA, le16(file.deviceIndex));

        long downloaded = 0;
        long total = file.sizeKb * 1024L;
        while (true) {
            Packet packet = readPacket();
            if (packet.type == TYPE_NAK) {
                throw new IOException("Camera rejected download: 0x" + Integer.toHexString(packet.error));
            }
            if (packet.payload.length == 0) break;
            chunks.accept(packet.payload);
            downloaded += packet.payload.length;
            progress.accept(downloaded, total);
        }
    }

    public byte[] thumbnail(CameraFile file) throws IOException {
        ensureConnected();
        setPlaybackMode();
        sendCommand(MODE_PLAYBACK, PB_THUMBNAIL, le16(file.deviceIndex));

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        while (true) {
            Packet packet = readPacket();
            if (packet.type == TYPE_NAK) throw new IOException("Thumbnail rejected");
            if (packet.payload.length == 0) break;
            out.write(packet.payload);
        }
        return out.toByteArray();
    }

    public void delete(CameraFile file) throws IOException {
        ensureConnected();
        setPlaybackMode();
        transact(MODE_PLAYBACK, PB_DELETE, le16(file.deviceIndex));
    }

    private void authenticate() throws IOException {
        byte[] seed = new byte[4];
        new Random().nextBytes(seed);
        sendCommand(MODE_GENERAL, CMD_AUTH, seed);
        Packet packet = readPacket();

        if (packet.type != TYPE_ACK) throw new IOException("Camera rejected authentication");
        if (packet.payload.length >= 6) {
            int[] expected = generateAuthKey(seed, packet.payload);
            int key0 = packet.payload[4] & 0xff;
            int key1 = packet.payload[5] & 0xff;
            if (key0 != expected[0] || key1 != expected[1]) {
                throw new IOException("Camera authentication response mismatch");
            }
        }
    }

    private int[] generateAuthKey(byte[] seed, byte[] payload) {
        int s0 = seed[0] & 0xff;
        int s1 = seed[1] & 0xff;
        int s2 = seed[2] & 0xff;
        int s3 = seed[3] & 0xff;
        int p0 = payload[0] & 0xff;
        int p1 = payload[1] & 0xff;
        int p2 = payload[2] & 0xff;
        int p3 = payload[3] & 0xff;

        int magic = (s0 ^ p0) | ((s1 ^ p1) << 8);
        int key = (s2 ^ p2) | ((s3 ^ p3) << 8);
        int period = ((magic & 0xff) ^ ((magic >>> 8) & 0xff)) & 0x0f;
        period = ((AUTH_LUT[period] ^ s0) + (AUTH_LUT[15 - period] ^ s1)) & 0xff;

        for (int i = 0; i < period; i++) {
            int bit = (key ^ (key >>> 2) ^ (key >>> 3) ^ (key >>> 5)) & 1;
            key = ((bit << 15) | (key >>> 1)) & 0xffff;
        }
        return new int[]{key & 0xff, (key >>> 8) & 0xff};
    }

    private Packet transact(int mode, int command, byte[] payload) throws IOException {
        ensureConnected();
        sendCommand(mode, command, payload);
        Packet packet = readPacket();
        if (packet.type == TYPE_NAK) {
            throw new IOException("Camera error 0x" + Integer.toHexString(packet.error));
        }
        return packet;
    }

    private void sendCommand(int mode, int command, byte[] payload) throws IOException {
        if (output == null) throw new IOException("Not connected");
        ByteBuffer header = ByteBuffer.allocate(12)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("GPSOCKET".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .putShort((short) TYPE_CMD)
                .put((byte) mode)
                .put((byte) command);
        output.write(header.array());
        if (payload.length > 0) output.write(payload);
        output.flush();
    }

    private Packet readPacket() throws IOException {
        if (input == null) throw new IOException("Not connected");
        byte[] tag = readExact(8);
        byte[] expected = "GPSOCKET".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (!java.util.Arrays.equals(tag, expected)) throw new IOException("Unexpected camera packet");

        int type = u16(readExact(2), 0);
        byte[] modeCmd = readExact(2);
        int mode = modeCmd[0] & 0xff;
        int command = modeCmd[1] & 0xff;

        if (type == TYPE_ACK) {
            int len = u16(readExact(2), 0);
            return new Packet(type, mode, command, len > 0 ? readExact(len) : new byte[0], 0);
        } else if (type == TYPE_NAK) {
            int error = u16(readExact(2), 0);
            return new Packet(type, mode, command, new byte[0], error);
        }
        return new Packet(type, mode, command, new byte[0], 0);
    }

    private byte[] readExact(int size) throws IOException {
        byte[] data = new byte[size];
        int pos = 0;
        while (pos < size) {
            int n = input.read(data, pos, size - pos);
            if (n < 0) throw new EOFException("Camera closed connection");
            pos += n;
        }
        return data;
    }

    private void ensureConnected() throws IOException {
        if (socket == null || !socket.isConnected() || socket.isClosed()) {
            throw new IOException("No camera connection");
        }
    }

    private static byte[] le16(int value) {
        return new byte[]{(byte) (value & 0xff), (byte) ((value >>> 8) & 0xff)};
    }

    private static int u16(byte[] bytes, int off) {
        return (bytes[off] & 0xff) | ((bytes[off + 1] & 0xff) << 8);
    }

    private static long u32(byte[] bytes, int off) {
        return ((long) bytes[off] & 0xffL)
                | (((long) bytes[off + 1] & 0xffL) << 8)
                | (((long) bytes[off + 2] & 0xffL) << 16)
                | (((long) bytes[off + 3] & 0xffL) << 24);
    }

    private static final class Packet {
        final int type;
        final int mode;
        final int command;
        final byte[] payload;
        final int error;

        Packet(int type, int mode, int command, byte[] payload, int error) {
            this.type = type;
            this.mode = mode;
            this.command = command;
            this.payload = payload;
            this.error = error;
        }
    }

    public static final class CameraFile {
        public final char extCode;
        public final int deviceIndex;
        public final int year;
        public final int month;
        public final int day;
        public final int hour;
        public final int minute;
        public final int second;
        public final long sizeKb;

        CameraFile(char extCode, int deviceIndex, int year, int month, int day,
                   int hour, int minute, int second, long sizeKb) {
            this.extCode = extCode;
            this.deviceIndex = deviceIndex;
            this.year = year;
            this.month = month;
            this.day = day;
            this.hour = hour;
            this.minute = minute;
            this.second = second;
            this.sizeKb = sizeKb;
        }

        public boolean isImage() {
            return extCode == 'J';
        }

        public String guessedExtension() {
            if (extCode == 'J') return "jpg";
            if (extCode == 'V' || extCode == 'K' || extCode == 'O') return "avi";
            return "mov";
        }

        public String displayName() {
            String prefix;
            if (extCode == 'J') prefix = "PICT";
            else if (extCode == 'L' || extCode == 'K') prefix = "LOCK";
            else if (extCode == 'S' || extCode == 'O') prefix = "SOS0";
            else prefix = "MOVI";
            return String.format(Locale.US, "%s%04d.%s", prefix, deviceIndex, guessedExtension());
        }

        public String details() {
            String size = sizeKb >= 1024
                    ? String.format(Locale.US, "%.1f MB", sizeKb / 1024.0)
                    : sizeKb + " KB";
            return String.format(Locale.US, "%s  •  %02d.%02d.%04d %02d:%02d",
                    size, day, month, year, hour, minute);
        }

        @Override public String toString() {
            return displayName() + "\n" + details();
        }
    }
}
