package com.cashewpeanut.cattoy;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

public class MainActivity extends Activity {
    public static final int PORT = 8080;
    // Home Wi-Fi server (http://<projector-ip>:8080). Off: control only via the Cloudflare site.
    public static final boolean LOCAL_SERVER = false;
    public static final String RELAY_HOST = "thecatgame.pages.dev";
    // Single shared room: anyone on the site controls this projector.
    public static final String ROOM = "HOME";

    private PointerView view;
    private ToyServer server;
    private RelayClient relay;
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("cattoy", MODE_PRIVATE);

        view = new PointerView(this);
        setContentView(view);

        JSONObject settings = loadSettings();
        view.applySettings(settings);

        String ip = LOCAL_SERVER ? localIp() : null;
        view.setHint("Open  " + RELAY_HOST
                + (ip == null ? "" : "\nor on home Wi-Fi:  http://" + ip + ":" + PORT));

        server = new ToyServer(PORT, getAssets(), settings, new ToyServer.Listener() {
            @Override public void onPointer(float x, float y) { view.setPointer(x, y); }
            @Override public void onRelease() { view.release(); }
            @Override public void onSettings(JSONObject s) {
                prefs.edit().putString("settings", s.toString()).apply();
                view.post(() -> view.applySettings(s));
            }
            @Override public void onClientCount(int n) { view.setClientCount(n); }
        });
        if (LOCAL_SERVER) server.start();

        relay = new RelayClient(RELAY_HOST, ROOM, server);
        server.setRelay(relay);
        relay.start();

        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l > 0 && b - t > 0) server.setScreenSize(r - l, b - t);
        });
    }

    private JSONObject loadSettings() {
        JSONObject s = new JSONObject();
        try {
            s.put("shape", "dot");
            s.put("color", "#ff2d55");
            s.put("size", 0.06);
            s.put("smoothing", 0.25);
            s.put("trail", false);
            s.put("hideOnRelease", false);
            s.put("auto", false);
            s.put("autoSpeed", 1.0);
            String saved = prefs.getString("settings", null);
            if (saved != null) {
                JSONObject j = new JSONObject(saved);
                for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) {
                    String k = it.next();
                    s.put(k, j.get(k));
                }
            }
        } catch (Exception ignored) {}
        return s;
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override protected void onDestroy() {
        if (relay != null) relay.stop();
        if (server != null) server.stop();
        super.onDestroy();
    }

    private static String localIp() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) return addr.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
