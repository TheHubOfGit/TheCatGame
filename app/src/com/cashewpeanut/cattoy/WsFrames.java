package com.cashewpeanut.cattoy;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;

/** Minimal WebSocket (RFC 6455) frame reading/writing shared by the local server and the relay client. */
final class WsFrames {
    static final int TEXT = 0x1, CLOSE = 0x8, PING = 0x9, PONG = 0xA;
    private static final SecureRandom RNG = new SecureRandom();

    static final class Frame {
        final int opcode;
        final byte[] payload;
        Frame(int opcode, byte[] payload) { this.opcode = opcode; this.payload = payload; }
    }

    private WsFrames() {}

    /** Returns null on clean end of stream. */
    static Frame read(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = readByte(in);
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = (readByte(in) << 8) | readByte(in);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
        }
        if (len > 65536) throw new IOException("Frame too large");
        byte[] mask = masked ? readFully(in, 4) : null;
        byte[] payload = readFully(in, (int) len);
        if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
        return new Frame(b0 & 0x0F, payload);
    }

    /** Clients must mask frames; servers must not. Caller synchronizes on the stream. */
    static void write(OutputStream out, int opcode, byte[] payload, boolean mask) throws IOException {
        int len = payload.length;
        int maskBit = mask ? 0x80 : 0;
        byte[] header;
        if (len < 126) {
            header = new byte[]{(byte) (0x80 | opcode), (byte) (maskBit | len)};
        } else if (len < 65536) {
            header = new byte[]{(byte) (0x80 | opcode), (byte) (maskBit | 126), (byte) (len >> 8), (byte) len};
        } else {
            header = new byte[10];
            header[0] = (byte) (0x80 | opcode);
            header[1] = (byte) (maskBit | 127);
            for (int i = 0; i < 8; i++) header[9 - i] = (byte) ((long) len >> (8 * i));
        }
        out.write(header);
        if (mask) {
            byte[] key = new byte[4];
            RNG.nextBytes(key);
            byte[] masked = new byte[len];
            for (int i = 0; i < len; i++) masked[i] = (byte) (payload[i] ^ key[i & 3]);
            out.write(key);
            out.write(masked);
        } else {
            out.write(payload);
        }
        out.flush();
    }

    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("Header too long");
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
        return buf;
    }
}
