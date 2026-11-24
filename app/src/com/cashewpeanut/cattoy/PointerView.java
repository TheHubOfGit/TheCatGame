package com.cashewpeanut.cattoy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Choreographer;
import android.view.View;

import org.json.JSONObject;

/** Full-screen black view that draws the "prey" (dot / fish / bird) at the remote pointer position. */
public class PointerView extends View implements Choreographer.FrameCallback {
    private static final int TRAIL_LEN = 24;

    // Written from the network thread.
    private volatile float targetX = 0.5f, targetY = 0.5f;
    private volatile boolean hasTarget = false;
    private volatile boolean released = true;
    private volatile int clientCount = 0;

    // Settings (main thread).
    private String shape = "dot";
    private int color = Color.parseColor("#ff2d55");
    private float size = 0.06f;       // fraction of screen height
    private float smoothing = 0.25f;  // 0 = instant, 1 = very floaty
    private boolean trail = false;
    private boolean hideOnRelease = false;
    private boolean auto = false;
    private float autoLevel = 1f;   // 0 = slow, 1 = medium, 2 = fast, 3 = crazy (continuous)
    private String hint = "";

    private static final long MANUAL_OVERRIDE_NS = 15_000_000_000L;
    private static final long AUTO_RESUME_NS = 1_500_000_000L;
    private volatile long lastManualNs = 0;
    private final java.util.Random rng = new java.util.Random();

    // Auto-movement "critter" state (pixels).
    private float ax, ay, aHeading, aSpeed, aGoalX, aGoalY, aWiggle;
    private float aPause = 0f, aLegTime = 0f, aDash = 1f;
    private boolean aInit = false;

    // Animation state (main thread).
    private float x, y, vx, vy;
    private boolean shown = false;
    private boolean placed = false;
    private float facing = 1f, tilt = 0f, phase = 0f, alpha = 0f;
    private long lastFrameNs = 0;
    private final float[] trailX = new float[TRAIL_LEN], trailY = new float[TRAIL_LEN];
    private int trailHead = 0, trailCount = 0;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private RadialGradient glowShader;
    private int glowShaderColor;
    private float glowShaderRadius;

    public PointerView(Context ctx) {
        super(ctx);
        setBackgroundColor(Color.BLACK);
        text.setColor(Color.rgb(90, 90, 90));
        text.setTextAlign(Paint.Align.CENTER);
    }

    public void setHint(String hint) { this.hint = hint; }

    public void setClientCount(int n) {
        clientCount = n;
        if (n == 0) { hasTarget = false; released = true; }
    }

    public void setPointer(float nx, float ny) {
        targetX = nx;
        targetY = ny;
        released = false;
        hasTarget = true;
        lastManualNs = System.nanoTime();
    }

    public void release() { released = true; }

    public void applySettings(JSONObject s) {
        shape = s.optString("shape", shape);
        try { color = Color.parseColor(s.optString("color", "#ff2d55")); } catch (IllegalArgumentException ignored) {}
        size = (float) s.optDouble("size", size);
        smoothing = (float) s.optDouble("smoothing", smoothing);
        trail = s.optBoolean("trail", trail);
        hideOnRelease = s.optBoolean("hideOnRelease", hideOnRelease);
        boolean wasAuto = auto;
        auto = s.optBoolean("auto", auto);
        autoLevel = parseLevel(s.opt("autoSpeed"), autoLevel);
        if (auto && !wasAuto) aInit = false;
    }

    private static final float[] SPEED_NOTCHES = {0.18f, 0.45f, 0.9f, 1.7f};

    private static float parseLevel(Object v, float fallback) {
        if (v instanceof Number) return Math.max(0f, Math.min(3f, ((Number) v).floatValue()));
        if (v instanceof String) {   // older saved settings used names
            switch ((String) v) {
                case "slow": return 0f;
                case "medium": return 1f;
                case "fast": return 2f;
                case "crazy": return 3f;
            }
        }
        return fallback;
    }

    /** Top speed in screen-heights per second, interpolated between the notches. */
    private float autoTopSpeed() {
        int i = Math.min(2, (int) autoLevel);
        float f = autoLevel - i;
        return SPEED_NOTCHES[i] + (SPEED_NOTCHES[i + 1] - SPEED_NOTCHES[i]) * f;
    }

    /** Wandering prey: curves toward random goals, pauses, occasionally darts. */
    private void stepAuto(float dt, int w, int h) {
        float top = autoTopSpeed() * h;
        float crazy = Math.max(0f, autoLevel - 2f);   // 0..1: twitchier, fewer pauses
        if (!aInit) {
            ax = x != 0 || y != 0 ? x : w / 2f;
            ay = x != 0 || y != 0 ? y : h / 2f;
            aHeading = rng.nextFloat() * 6.283f;
            aSpeed = 0;
            aPause = 0;
            pickGoal(w, h);
            aInit = true;
        }
        if (aPause > 0) {
            aPause -= dt;
            aSpeed = Math.max(0, aSpeed - top * 6f * dt);
        } else {
            aLegTime += dt;
            float dx = aGoalX - ax, dy = aGoalY - ay;
            float dist = (float) Math.hypot(dx, dy);
            float want = (float) Math.atan2(dy, dx);
            float diff = (float) Math.atan2(Math.sin(want - aHeading), Math.cos(want - aHeading));
            // Twitchy heading noise makes the path feel alive.
            aWiggle += dt * (4f + 5f * crazy);
            float noise = (float) Math.sin(aWiggle) * 0.8f + (float) Math.sin(aWiggle * 2.3f) * 0.5f;
            float turnRate = 2.5f + autoTopSpeed() * 4f;
            aHeading += Math.max(-turnRate * dt, Math.min(turnRate * dt, diff + noise * dt * 3f));
            float vmax = top * aDash * Math.max(0.25f, Math.min(1f, dist / (0.12f * h)));
            aSpeed += (vmax - aSpeed) * Math.min(1f, dt * 4f);

            boolean arrived = dist < Math.max(0.03f * h, aSpeed * 0.12f);
            if (arrived || aLegTime > 5f) {
                float pauseChance = 0.6f - 0.35f * crazy;
                if (rng.nextFloat() < pauseChance) {
                    aPause = (0.3f - 0.2f * crazy) + rng.nextFloat() * (1.8f - 1.2f * crazy);
                }
                pickGoal(w, h);
            }
        }
        ax += (float) Math.cos(aHeading) * aSpeed * dt;
        ay += (float) Math.sin(aHeading) * aSpeed * dt;
        // Soft walls: bounce the heading back inward.
        float m = 0.04f * h;
        if (ax < m || ax > w - m) { aHeading = (float) Math.PI - aHeading; ax = Math.max(m, Math.min(w - m, ax)); }
        if (ay < m || ay > h - m) { aHeading = -aHeading; ay = Math.max(m, Math.min(h - m, ay)); }
    }

    private void pickGoal(int w, int h) {
        aLegTime = 0;
        float mx = 0.08f * w, my = 0.1f * h;
        if (rng.nextFloat() < 0.35f) {
            // Short skitter near the current spot.
            float r = 0.12f * h + rng.nextFloat() * 0.15f * h;
            float a = rng.nextFloat() * 6.283f;
            aGoalX = Math.max(mx, Math.min(w - mx, ax + (float) Math.cos(a) * r));
            aGoalY = Math.max(my, Math.min(h - my, ay + (float) Math.sin(a) * r));
        } else {
            aGoalX = mx + rng.nextFloat() * (w - 2 * mx);
            aGoalY = my + rng.nextFloat() * (h - 2 * my);
        }
        // Occasionally dart.
        aDash = rng.nextFloat() < 0.2f ? 1.8f : 0.7f + rng.nextFloat() * 0.5f;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this);
        super.onDetachedFromWindow();
    }

    @Override public void doFrame(long frameTimeNanos) {
        float dt = lastFrameNs == 0 ? 1f / 60f : (frameTimeNanos - lastFrameNs) / 1e9f;
        lastFrameNs = frameTimeNanos;
        dt = Math.max(0.001f, Math.min(dt, 0.1f));
        step(dt);
        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void step(float dt) {
        int w = getWidth(), h = getHeight();
        if (w == 0) return;
        float tx = targetX * w, ty = targetY * h;
        // Finger down (or mouse over the pad) takes over auto mode; auto resumes shortly after
        // release, or if a controller goes silent without releasing (e.g. dropped connection).
        long sinceManual = System.nanoTime() - lastManualNs;
        boolean manualActive = hasTarget && (released
                ? sinceManual < AUTO_RESUME_NS
                : sinceManual < MANUAL_OVERRIDE_NS);
        boolean autoActive = auto && !manualActive;
        if (autoActive) {
            stepAuto(dt, w, h);
            tx = ax; ty = ay;
        } else {
            aInit = false;   // resume auto from wherever the pointer ends up
        }

        boolean wantShown = autoActive || (hasTarget && !(released && hideOnRelease));
        if (autoActive && !placed) { x = tx; y = ty; placed = true; }
        if (wantShown && !placed) { x = tx; y = ty; placed = true; }
        if (!hasTarget && !autoActive) placed = false;
        shown = wantShown;
        alpha += ((shown ? 1f : 0f) - alpha) * Math.min(1f, dt * 12f);

        float px = x, py = y;
        if (smoothing <= 0.01f) {
            x = tx; y = ty;
        } else {
            // Higher smoothing -> lower follow rate (per second).
            float rate = 40f * (float) Math.pow(1f - smoothing, 2) + 2f;
            float k = 1f - (float) Math.exp(-rate * dt);
            x += (tx - x) * k;
            y += (ty - y) * k;
        }
        vx += ((x - px) / dt - vx) * Math.min(1f, dt * 10f);
        vy += ((y - py) / dt - vy) * Math.min(1f, dt * 10f);
        float speed = (float) Math.hypot(vx, vy);

        // Facing / tilt for fish and bird.
        if (Math.abs(vx) > h * 0.05f) facing = vx > 0 ? 1f : -1f;
        float wantTilt = speed > h * 0.05f
                ? (float) Math.atan2(vy, Math.abs(vx)) : 0f;
        wantTilt = Math.max(-0.7f, Math.min(0.7f, wantTilt));
        tilt += (wantTilt - tilt) * Math.min(1f, dt * 8f);

        // Wiggle / flap speed scales with movement.
        float speedNorm = Math.min(speed / h, 3f);
        phase += dt * (5f + speedNorm * 12f);

        if (trail && shown) {
            trailHead = (trailHead + 1) % TRAIL_LEN;
            trailX[trailHead] = x;
            trailY[trailHead] = y;
            trailCount = Math.min(trailCount + 1, TRAIL_LEN);
        } else {
            trailCount = 0;
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        int h = getHeight();
        if (clientCount == 0 && !auto && !hint.isEmpty()) {
            text.setTextSize(h * 0.03f);
            String[] lines = hint.split("\n");
            for (int i = 0; i < lines.length; i++) {
                canvas.drawText(lines[i], getWidth() / 2f, h * (0.93f - 0.045f * (lines.length - 1 - i)), text);
            }
        }
        if (alpha < 0.01f) return;

        float r = size * h / 2f;

        if (trailCount > 1) {
            for (int i = trailCount - 1; i >= 1; i--) {
                int idx = (trailHead - i + TRAIL_LEN) % TRAIL_LEN;
                float t = 1f - i / (float) trailCount;
                fill.setColor(withAlpha(color, (int) (110 * t * alpha)));
                canvas.drawCircle(trailX[idx], trailY[idx], r * (0.25f + 0.45f * t), fill);
            }
        }

        canvas.save();
        canvas.translate(x, y);
        switch (shape) {
            case "fish": drawFish(canvas, r); break;
            case "bird": drawBird(canvas, r); break;
            default: drawDot(canvas, r); break;
        }
        canvas.restore();
    }

    private void drawDot(Canvas c, float r) {
        float gr = r * 2.6f;
        if (glowShader == null || glowShaderColor != color || glowShaderRadius != gr) {
            glowShader = new RadialGradient(0, 0, gr,
                    new int[]{withAlpha(color, 150), withAlpha(color, 40), withAlpha(color, 0)},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
            glowShaderColor = color;
            glowShaderRadius = gr;
        }
        glow.setShader(glowShader);
        glow.setAlpha((int) (255 * alpha));
        c.drawCircle(0, 0, gr, glow);
        fill.setColor(withAlpha(color, (int) (255 * alpha)));
        c.drawCircle(0, 0, r, fill);
        fill.setColor(withAlpha(lighten(color, 0.6f), (int) (230 * alpha)));
        c.drawCircle(-r * 0.3f, -r * 0.3f, r * 0.3f, fill);
    }

    private void drawFish(Canvas c, float r) {
        c.scale(facing, 1f);
        c.rotate((float) Math.toDegrees(tilt));
        int a = (int) (255 * alpha);
        int dark = withAlpha(darken(color, 0.35f), a);
        float wig = (float) Math.sin(phase) * 0.45f;

        // Tail (wiggles around the body's back end).
        c.save();
        c.rotate((float) Math.toDegrees(wig), -r * 0.9f, 0);
        path.reset();
        path.moveTo(-r * 0.8f, 0);
        path.lineTo(-r * 1.9f, -r * 0.75f);
        path.quadTo(-r * 1.55f, 0, -r * 1.9f, r * 0.75f);
        path.close();
        fill.setColor(dark);
        c.drawPath(path, fill);
        c.restore();

        // Dorsal fin.
        path.reset();
        path.moveTo(-r * 0.4f, -r * 0.5f);
        path.quadTo(-r * 0.1f, -r * 1.05f, r * 0.35f, -r * 0.5f);
        path.close();
        fill.setColor(dark);
        c.drawPath(path, fill);

        // Body.
        rect.set(-r * 1.15f, -r * 0.62f, r * 1.15f, r * 0.62f);
        fill.setColor(withAlpha(color, a));
        c.drawOval(rect, fill);

        // Side fin (flaps a little).
        c.save();
        c.rotate((float) Math.toDegrees(-wig * 0.6f), r * 0.1f, r * 0.15f);
        rect.set(-r * 0.35f, r * 0.05f, r * 0.25f, r * 0.42f);
        fill.setColor(dark);
        c.drawOval(rect, fill);
        c.restore();

        // Eye.
        fill.setColor(withAlpha(Color.WHITE, a));
        c.drawCircle(r * 0.6f, -r * 0.15f, r * 0.2f, fill);
        fill.setColor(withAlpha(Color.BLACK, a));
        c.drawCircle(r * 0.66f, -r * 0.15f, r * 0.1f, fill);
    }

    private void drawBird(Canvas c, float r) {
        c.scale(facing, 1f);
        c.rotate((float) Math.toDegrees(tilt * 0.6f));
        int a = (int) (255 * alpha);
        int dark = withAlpha(darken(color, 0.35f), a);
        float flap = (float) Math.sin(phase * 1.6f);

        // Tail feathers.
        path.reset();
        path.moveTo(-r * 0.7f, -r * 0.05f);
        path.lineTo(-r * 1.6f, -r * 0.45f);
        path.lineTo(-r * 1.5f, r * 0.05f);
        path.lineTo(-r * 1.65f, r * 0.4f);
        path.lineTo(-r * 0.7f, r * 0.2f);
        path.close();
        fill.setColor(dark);
        c.drawPath(path, fill);

        // Far wing (behind body).
        drawWing(c, r, -flap * 0.8f, withAlpha(darken(color, 0.5f), a));

        // Body.
        rect.set(-r * 1.0f, -r * 0.55f, r * 0.75f, r * 0.55f);
        fill.setColor(withAlpha(color, a));
        c.drawOval(rect, fill);
        // Belly.
        rect.set(-r * 0.6f, -r * 0.05f, r * 0.6f, r * 0.5f);
        fill.setColor(withAlpha(lighten(color, 0.45f), a));
        c.drawOval(rect, fill);

        // Head.
        fill.setColor(withAlpha(color, a));
        c.drawCircle(r * 0.8f, -r * 0.35f, r * 0.45f, fill);

        // Beak.
        path.reset();
        path.moveTo(r * 1.15f, -r * 0.48f);
        path.lineTo(r * 1.65f, -r * 0.3f);
        path.lineTo(r * 1.15f, -r * 0.15f);
        path.close();
        fill.setColor(withAlpha(Color.rgb(255, 170, 0), a));
        c.drawPath(path, fill);

        // Eye.
        fill.setColor(withAlpha(Color.WHITE, a));
        c.drawCircle(r * 0.92f, -r * 0.45f, r * 0.15f, fill);
        fill.setColor(withAlpha(Color.BLACK, a));
        c.drawCircle(r * 0.96f, -r * 0.45f, r * 0.08f, fill);

        // Near wing (in front of body).
        drawWing(c, r, flap, dark);
    }

    private void drawWing(Canvas c, float r, float flap, int col) {
        float tipY = -r * 0.1f - flap * r * 1.3f;
        path.reset();
        path.moveTo(-r * 0.45f, -r * 0.05f);
        path.quadTo(-r * 0.6f, tipY * 0.7f, -r * 0.55f, tipY);
        path.quadTo(r * 0.1f, tipY * 0.6f, r * 0.35f, -r * 0.05f);
        path.close();
        fill.setColor(col);
        c.drawPath(path, fill);
    }

    private static int withAlpha(int c, int a) {
        return (Math.max(0, Math.min(255, a)) << 24) | (c & 0x00FFFFFF);
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * (1 - f)), (int) (Color.green(c) * (1 - f)), (int) (Color.blue(c) * (1 - f)));
    }

    private static int lighten(int c, float f) {
        return Color.rgb((int) (Color.red(c) + (255 - Color.red(c)) * f),
                (int) (Color.green(c) + (255 - Color.green(c)) * f),
                (int) (Color.blue(c) + (255 - Color.blue(c)) * f));
    }
}
