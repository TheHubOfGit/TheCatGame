package com.cashewpeanut.cattoy;

import android.content.res.AssetManager;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tiny dependency-free HTTP + WebSocket server, plus the hub for relay (internet) controllers.
 *
 * GET /    -> controller web page (assets/index.html)
 * GET /ws  -> WebSocket. Controllers send:
 *               "p,<x>,<y>"   pointer position, normalized 0..1
 *               "u"           pointer released / left the pad
 *               "s{...json}"  settings patch
 *             The projector sends:
 *               "c{...json}"  full config (settings + screen size + controller count)
 */
public class ToyServer {
    private static final String TAG = "CatToy";
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    public interface Listener {
        void onPointer(float x, float y);
        void onRelease();
        void onSettings(JSONObject settings);
        void onClientCount(int count);
    }

    /** Somewhere config broadcasts are also sent (the cloud relay). */
    public interface Sink {
        void send(String msg);
    }

    private final int port;
    private final AssetManager assets;
    private final Listener listener;
    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();
    private final JSONObject settings;
    private int screenW = 1280, screenH = 720;
    private volatile int remoteCount = 0;
    private volatile Sink relay;
    private ServerSocket serverSocket;
    private volatile boolean running;

    public ToyServer(int port, AssetManager assets, JSONObject initialSettings, Listener listener) {
        this.port = port;
        this.assets = assets;
        this.settings = initialSettings;
        this.listener = listener;
    }

    public void setRelay(Sink relay) { this.relay = relay; }

    /** Number of controllers connected through the cloud relay. */
    public void setRemoteCount(int n) {
        remoteCount = n;
        listener.onClientCount(totalClients());
        broadcastConfig();
    }

    public void setScreenSize(int w, int h) {
        synchronized (settings) {
            screenW = w;
            screenH = h;
        }
        broadcastConfig();
    }

    private int totalClients() { return clients.size() + remoteCount; }

    public void start() {
        running = true;
        Thread t = new Thread(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(port));
                Log.i(TAG, "Listening on port " + port);
                while (running) {
                    Socket s = serverSocket.accept();
                    new Thread(() -> handle(s), "cattoy-conn").start();
                }
            } catch (IOException e) {
                if (running) Log.e(TAG, "Server error", e);
            }
        }, "cattoy-server");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        for (Client c : clients) c.close();
        clients.clear();
    }

    // ---------------------------------------------------------------- HTTP

    private void handle(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            String requestLine = WsFrames.readLine(in);
            if (requestLine == null) { socket.close(); return; }
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = WsFrames.readLine(in)) != null && !line.isEmpty()) {
                int i = line.indexOf(':');
                if (i > 0) headers.put(line.substring(0, i).trim().toLowerCase(Locale.US), line.substring(i + 1).trim());
            }
            String[] parts = requestLine.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            String upgrade = headers.get("upgrade");
            if ("/ws".equals(path) && upgrade != null && upgrade.equalsIgnoreCase("websocket")) {
                handleWebSocket(socket, in, out, headers.get("sec-websocket-key"));
                return;
            }

            if ("/".equals(path) || "/index.html".equals(path)) {
                sendResponse(out, "200 OK", "text/html; charset=utf-8", readAsset("index.html"));
            } else {
                sendResponse(out, "404 Not Found", "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            }
            socket.close();
        } catch (Exception e) {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void sendResponse(OutputStream out, String status, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private byte[] readAsset(String name) throws IOException {
        try (InputStream is = assets.open(name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    // ----------------------------------------------------------- WebSocket

    private void handleWebSocket(Socket socket, InputStream in, OutputStream out, String key) throws Exception {
        if (key == null) { socket.close(); return; }
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        String accept = Base64.encodeToString(
                sha1.digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII)), Base64.NO_WRAP);
        out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();

        Client client = new Client(socket, out);
        clients.add(client);
        listener.onClientCount(totalClients());
        broadcastConfig();

        try {
            WsFrames.Frame f;
            while (running && (f = WsFrames.read(in)) != null) {
                if (f.opcode == WsFrames.CLOSE) {
                    client.send(WsFrames.CLOSE, new byte[0]);
                    break;
                } else if (f.opcode == WsFrames.PING) {
                    client.send(WsFrames.PONG, f.payload);
                } else if (f.opcode == WsFrames.TEXT) {
                    onMessage(new String(f.payload, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException ignored) {
        } finally {
            client.close();
            clients.remove(client);
            listener.onClientCount(totalClients());
            broadcastConfig();
        }
    }

    /** Handles a controller message from either the local socket or the relay. */
    public void onMessage(String msg) {
        if (msg.isEmpty()) return;
        char kind = msg.charAt(0);
        try {
            if (kind == 'p') {
                String[] p = msg.split(",");
                float x = clamp01(Float.parseFloat(p[1]));
                float y = clamp01(Float.parseFloat(p[2]));
                listener.onPointer(x, y);
            } else if (kind == 'u') {
                listener.onRelease();
            } else if (kind == 's') {
                JSONObject patch = new JSONObject(msg.substring(1));
                JSONObject copy;
                synchronized (settings) {
                    Iterator<String> keys = patch.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        Object v = sanitize(k, patch.get(k));
                        if (v != null) settings.put(k, v);
                    }
                    copy = new JSONObject(settings.toString());
                }
                listener.onSettings(copy);
                broadcastConfig();
            }
        } catch (Exception e) {
            Log.w(TAG, "Bad message: " + msg, e);
        }
    }

    public void broadcastConfig() {
        String msg;
        try {
            synchronized (settings) {
                JSONObject cfg = new JSONObject();
                cfg.put("settings", new JSONObject(settings.toString()));
                cfg.put("screenW", screenW);
                cfg.put("screenH", screenH);
                cfg.put("clients", totalClients());
                msg = "c" + cfg;
            }
        } catch (Exception e) {
            return;
        }
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);
        for (Client c : clients) {
            try { c.send(WsFrames.TEXT, data); } catch (IOException e) { c.close(); }
        }
        Sink r = relay;
        if (r != null) r.send(msg);
    }

    /** Controllers can come from the internet: accept only known settings with sane values. */
    private static Object sanitize(String key, Object v) {
        switch (key) {
            case "shape":
                return "dot".equals(v) || "fish".equals(v) || "bird".equals(v) ? v : null;
            case "color":
                return v instanceof String && ((String) v).matches("#[0-9a-fA-F]{6}") ? v : null;
            case "size":
                return v instanceof Number ? clamp(((Number) v).doubleValue(), 0.01, 0.3) : null;
            case "smoothing":
                return v instanceof Number ? clamp(((Number) v).doubleValue(), 0, 0.95) : null;
            case "autoSpeed":
                return v instanceof Number ? clamp(((Number) v).doubleValue(), 0, 3) : null;
            case "trail":
            case "hideOnRelease":
            case "auto":
                return v instanceof Boolean ? v : null;
            default:
                return null;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Double.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
    }

    private static float clamp01(float v) {
        return Float.isNaN(v) ? 0.5f : Math.max(0f, Math.min(1f, v));
    }

    private static class Client {
        final Socket socket;
        final OutputStream out;

        Client(Socket socket, OutputStream out) {
            this.socket = socket;
            this.out = out;
        }

        synchronized void send(int opcode, byte[] payload) throws IOException {
            WsFrames.write(out, opcode, payload, false);
        }

        void close() {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}
