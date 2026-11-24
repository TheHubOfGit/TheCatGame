package com.cashewpeanut.cattoy;

import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Keeps an outbound WebSocket open to the Cloudflare relay so controllers anywhere on the
 * internet can reach the projector. Reconnects forever with backoff.
 *
 * Relay -> projector: controller messages verbatim, plus "n<count>" (connected controllers).
 * Projector -> relay: "c{...}" config, fanned out to every controller in the room.
 */
public class RelayClient implements ToyServer.Sink {
    private static final String TAG = "CatToyRelay";

    private final String host;
    private final String room;
    private final ToyServer server;
    private volatile boolean running;
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile boolean connected;

    public RelayClient(String host, String room, ToyServer server) {
        this.host = host;
        this.room = room;
        this.server = server;
    }

    public boolean isConnected() { return connected; }

    public void start() {
        running = true;
        Thread t = new Thread(this::loop, "cattoy-relay");
        t.setDaemon(true);
        t.start();
        Thread k = new Thread(() -> {
            while (running) {
                try { Thread.sleep(25_000); } catch (InterruptedException e) { return; }
                send("k");   // keepalive; relay auto-responds without waking up
            }
        }, "cattoy-relay-keepalive");
        k.setDaemon(true);
        k.start();
    }

    public void stop() {
        running = false;
        closeSocket();
    }

    @Override public void send(String msg) {
        OutputStream o = out;
        if (o == null) return;
        try {
            synchronized (this) {
                WsFrames.write(o, WsFrames.TEXT, msg.getBytes(StandardCharsets.UTF_8), true);
            }
        } catch (IOException e) {
            closeSocket();
        }
    }

    private void loop() {
        long backoff = 1000;
        while (running) {
            try {
                connectOnce();
                backoff = 1000;
            } catch (Exception e) {
                Log.w(TAG, "Relay connection failed: " + e);
            } finally {
                connected = false;
                out = null;
                closeSocket();
                server.setRemoteCount(0);
            }
            if (!running) break;
            try { Thread.sleep(backoff); } catch (InterruptedException e) { break; }
            backoff = Math.min(backoff * 2, 30_000);
        }
    }

    private void connectOnce() throws Exception {
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, 443), 10_000);
        SSLSocket s = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                .createSocket(raw, host, 443, true);
        s.startHandshake();
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.getSession())) {
            throw new IOException("TLS hostname mismatch for " + host);
        }
        s.setTcpNoDelay(true);
        s.setSoTimeout(70_000);   // keepalive replies arrive every 25s
        socket = s;

        byte[] keyBytes = new byte[16];
        new SecureRandom().nextBytes(keyBytes);
        String key = Base64.encodeToString(keyBytes, Base64.NO_WRAP);
        OutputStream o = s.getOutputStream();
        o.write(("GET /ws?role=device&room=" + room + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "User-Agent: CatToy/1.0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        o.flush();

        InputStream in = new BufferedInputStream(s.getInputStream());
        String status = WsFrames.readLine(in);
        if (status == null || !status.contains(" 101")) throw new IOException("Upgrade failed: " + status);
        String line;
        while ((line = WsFrames.readLine(in)) != null && !line.isEmpty()) { /* skip headers */ }

        out = o;
        connected = true;
        Log.i(TAG, "Connected to relay room " + room);
        server.broadcastConfig();

        WsFrames.Frame f;
        while (running && (f = WsFrames.read(in)) != null) {
            if (f.opcode == WsFrames.CLOSE) break;
            if (f.opcode == WsFrames.PING) {
                synchronized (this) { WsFrames.write(o, WsFrames.PONG, f.payload, true); }
                continue;
            }
            if (f.opcode != WsFrames.TEXT) continue;
            String msg = new String(f.payload, StandardCharsets.UTF_8);
            if (msg.isEmpty() || msg.equals("k")) continue;
            if (msg.charAt(0) == 'n') {
                try { server.setRemoteCount(Integer.parseInt(msg.substring(1))); } catch (NumberFormatException ignored) {}
            } else {
                server.onMessage(msg);
            }
        }
    }

    private void closeSocket() {
        Socket s = socket;
        socket = null;
        if (s != null) try { s.close(); } catch (IOException ignored) {}
    }
}
