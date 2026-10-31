package net.room309.stations;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Station Display: a full-screen station screen for classroom rotations.
 * It asks the Google Sheet web app (?api=state&n=<station>) what to show every 2 seconds.
 */
public class MainActivity extends Activity {

    private static final int NAVY = 0xFF0D1B2A;
    private static final int GOLD = 0xFFFFD166;
    private static final int SOFT = 0xFFC9D6E3;
    private static final int RED = 0xFFFF6B6B;
    private static final int GREEN = 0xFF2ECC71;

    private static final String P_STATION = "station";
    private static final String P_URL = "server_url";
    private static final String P_ORIENT = "orientation";

    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // Polling (background thread)
    private volatile boolean polling = false;
    private Thread poller;
    private volatile String haveHash = "";
    private volatile int fails = 0;

    // Latest server state (UI thread only)
    private JSONObject st;
    private long offset = 0;
    private String lastError = "";
    private String contentText = "";
    private boolean showingImage = false;

    // Views
    private FrameLayout root;
    private LinearLayout mainLayout, idleLayout, moveLayout;
    private LinearLayout bar;
    private FrameLayout card;
    private TextView badge, roundTv, timerTv, problemTv, bigNum, bigName, bigMsg, moveBig, moveMsg, moveCount, pausedTv;
    private ImageView problemImg;
    private View dot;
    private float density;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            render();
            ui.postDelayed(this, 250);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("station", MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        buildViews();
        setContentView(root);
        applyOrientation();
        setupDeviceOwnerIfAllowed();
        hideSystemBars();

        if (!prefs.contains(P_STATION) || serverUrl().length() == 0) {
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    showSettings(true);
                }
            }, 400);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        startLockTaskIfAllowed();
        startPolling();
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPolling();
        ui.removeCallbacks(ticker);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    public void onBackPressed() {
        // Back does nothing on a station screen.
    }

    // ------------------------------------------------------------------ settings helpers

    private int station() {
        return prefs.getInt(P_STATION, 1);
    }

    private String serverUrl() {
        String u = prefs.getString(P_URL, "");
        if (u == null || u.trim().length() == 0) u = BuildConfig.SERVER_URL;
        return u == null ? "" : u.trim();
    }

    private void applyOrientation() {
        int o = prefs.getInt(P_ORIENT, 0);
        if (o == 1) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        else if (o == 2) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        else setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private void applyBrightness(int pct) {
        if (pct < 1 || pct > 100) return;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        float want = Math.max(0.02f, pct / 100f);
        if (Math.abs(lp.screenBrightness - want) > 0.001f) {
            lp.screenBrightness = want;
            getWindow().setAttributes(lp);
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    // ------------------------------------------------------------------ optional lockdown

    private ComponentName admin() {
        return new ComponentName(this, AdminReceiver.class);
    }

    /** Only does anything if the tablet was made "device owner" with the one-time USB command. */
    private void setupDeviceOwnerIfAllowed() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                dpm.setLockTaskPackages(admin(), new String[]{getPackageName()});
                dpm.setKeyguardDisabled(admin(), true);
                dpm.setStatusBarDisabled(admin(), true);
                dpm.setGlobalSetting(admin(), Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "7");
            }
        } catch (Exception ignored) {
        }
    }

    private void startLockTaskIfAllowed() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isLockTaskPermitted(getPackageName())) startLockTask();
        } catch (Exception ignored) {
        }
    }

    private boolean isDeviceOwner() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            return dpm != null && dpm.isDeviceOwnerApp(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ views

    private int dp(float v) {
        return Math.round(v * density);
    }

    private TextView text(int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextColor(color);
        t.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setIncludeFontPadding(false);
        return t;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void buildViews() {
        root = new FrameLayout(this);
        root.setBackgroundColor(NAVY);

        // ---- main station layout
        mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        root.addView(mainLayout, new FrameLayout.LayoutParams(-1, -1));

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        mainLayout.addView(bar, new LinearLayout.LayoutParams(-1, 0, 15f));

        badge = text(NAVY, true);
        badge.setBackground(rounded(GOLD, 12));
        badge.setSingleLine(true);
        bar.addView(badge, new LinearLayout.LayoutParams(-2, -2));

        roundTv = text(SOFT, false);
        roundTv.setGravity(Gravity.CENTER);
        roundTv.setSingleLine(true);
        bar.addView(roundTv, new LinearLayout.LayoutParams(0, -2, 1f));

        timerTv = text(Color.WHITE, true);
        timerTv.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        timerTv.setSingleLine(true);
        bar.addView(timerTv, new LinearLayout.LayoutParams(-2, -2));

        card = new FrameLayout(this);
        card.setBackground(rounded(Color.WHITE, 16));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, 0, 82f);
        mainLayout.addView(card, cardLp);
        mainLayout.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 3f));

        problemTv = text(0xFF111111, false);
        problemTv.setGravity(Gravity.CENTER);
        problemTv.setLineSpacing(0, 1.15f);
        card.addView(problemTv, new FrameLayout.LayoutParams(-1, -1));

        problemImg = new ImageView(this);
        problemImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        problemImg.setVisibility(View.GONE);
        card.addView(problemImg, new FrameLayout.LayoutParams(-1, -1));

        pausedTv = text(GOLD, true);
        pausedTv.setText("Paused");
        pausedTv.setBackground(rounded(NAVY, 8));
        pausedTv.setPadding(dp(14), dp(8), dp(14), dp(8));
        pausedTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        pausedTv.setVisibility(View.GONE);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        plp.setMargins(0, 0, dp(28), dp(28));
        root.addView(pausedTv, plp);

        // ---- idle / done / setup screen
        idleLayout = new LinearLayout(this);
        idleLayout.setOrientation(LinearLayout.VERTICAL);
        idleLayout.setGravity(Gravity.CENTER);
        idleLayout.setBackgroundColor(NAVY);
        bigNum = text(GOLD, true);
        bigNum.setGravity(Gravity.CENTER);
        bigName = text(SOFT, false);
        bigName.setGravity(Gravity.CENTER);
        bigMsg = text(Color.WHITE, false);
        bigMsg.setGravity(Gravity.CENTER);
        idleLayout.addView(bigNum, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.topMargin = dp(12);
        idleLayout.addView(bigName, nlp);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
        mlp.topMargin = dp(20);
        idleLayout.addView(bigMsg, mlp);
        root.addView(idleLayout, new FrameLayout.LayoutParams(-1, -1));

        // ---- MOVE! overlay
        moveLayout = new LinearLayout(this);
        moveLayout.setOrientation(LinearLayout.VERTICAL);
        moveLayout.setGravity(Gravity.CENTER);
        moveLayout.setBackgroundColor(0xF5FFD166);
        moveBig = text(NAVY, true);
        moveBig.setText("MOVE!");
        moveMsg = text(NAVY, false);
        moveMsg.setText("Go to your next station");
        moveCount = text(NAVY, true);
        moveLayout.addView(moveBig, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams mm = new LinearLayout.LayoutParams(-2, -2);
        mm.topMargin = dp(16);
        moveLayout.addView(moveMsg, mm);
        LinearLayout.LayoutParams mc = new LinearLayout.LayoutParams(-2, -2);
        mc.topMargin = dp(16);
        moveLayout.addView(moveCount, mc);
        moveLayout.setVisibility(View.GONE);
        root.addView(moveLayout, new FrameLayout.LayoutParams(-1, -1));

        // ---- connection dot
        dot = new View(this);
        GradientDrawable dd = new GradientDrawable();
        dd.setShape(GradientDrawable.OVAL);
        dd.setColor(GREEN);
        dot.setBackground(dd);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(10), dp(10), Gravity.BOTTOM | Gravity.END);
        dlp.setMargins(0, 0, dp(6), dp(6));
        root.addView(dot, dlp);

        // ---- hidden settings corner: press and hold 3 seconds
        View corner = new View(this);
        root.addView(corner, new FrameLayout.LayoutParams(dp(110), dp(110), Gravity.TOP | Gravity.START));
        final Runnable openPin = new Runnable() {
            @Override
            public void run() {
                askPin();
            }
        };
        corner.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    ui.postDelayed(openPin, 3000);
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    ui.removeCallbacks(openPin);
                }
                return true;
            }
        });

        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r - l != or - ol || b - t != ob - ot) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            sizeChrome();
                            fitText();
                        }
                    });
                }
            }
        });
    }

    /** Scale the bar, idle and move-screen text to the screen size. */
    private void sizeChrome() {
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0) return;
        float vmin = Math.min(w, h) / 100f;
        int side = Math.round(w * 0.03f);

        mainLayout.setPadding(side, 0, side, 0);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, vmin * 6f);
        badge.setPadding(Math.round(vmin * 2.5f), Math.round(vmin * 1.2f), Math.round(vmin * 2.5f), Math.round(vmin * 1.2f));
        roundTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, vmin * 4f);
        timerTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, vmin * 8f);
        int pad = Math.round(Math.min(w, h) * 0.035f);
        card.setPadding(pad, pad, pad, pad);

        bigNum.setTextSize(TypedValue.COMPLEX_UNIT_PX, h * 0.38f);
        bigName.setTextSize(TypedValue.COMPLEX_UNIT_PX, vmin * 6f);
        bigMsg.setTextSize(TypedValue.COMPLEX_UNIT_PX, vmin * 4.5f);
        moveBig.setTextSize(TypedValue.COMPLEX_UNIT_PX, vmin * 20f);
        moveMsg.setTextSize(TypedValue.COMPLEX_UNIT_PX, vmin * 6f);
        moveCount.setTextSize(TypedValue.COMPLEX_UNIT_PX, vmin * 14f);
    }

    /** Make the problem text as big as fits: whole lines first, wrapping only if that gets too small. */
    private void fitText() {
        if (showingImage || contentText.length() == 0) return;
        int w = card.getWidth() - card.getPaddingLeft() - card.getPaddingRight();
        int h = card.getHeight() - card.getPaddingTop() - card.getPaddingBottom();
        if (w <= 0 || h <= 0) return;

        TextPaint p = new TextPaint(problemTv.getPaint());
        String[] lines = contentText.split("\n");
        float min = 14 * density;
        float size = h / 2.2f;
        boolean fits = false;

        while (size > min) {
            p.setTextSize(size);
            float widest = 0;
            for (String line : lines) widest = Math.max(widest, p.measureText(line));
            float tall = lines.length * p.getFontSpacing() * 1.15f;
            if (widest <= w * 0.97f && tall <= h * 0.97f) {
                fits = true;
                break;
            }
            size *= 0.93f;
        }

        if (!fits || size < h / 9f) {
            size = h / 2.2f;
            while (size > min) {
                p.setTextSize(size);
                @SuppressWarnings("deprecation")
                StaticLayout sl = new StaticLayout(contentText, p, w, Layout.Alignment.ALIGN_CENTER, 1.15f, 0f, false);
                if (sl.getHeight() <= h * 0.97f) break;
                size *= 0.93f;
            }
        }
        problemTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(size, min));
    }

    // ------------------------------------------------------------------ polling

    private static class Result {
        JSONObject json;
        long at;
        boolean hasContent;
        String type = "text";
        String text = "";
        Bitmap bmp;
    }

    private void startPolling() {
        if (polling) return;
        polling = true;
        poller = new Thread(new Runnable() {
            @Override
            public void run() {
                while (polling) {
                    long wait = 2000;
                    try {
                        final Result r = fetch();
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                onResult(r);
                            }
                        });
                    } catch (final Exception e) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                onFail(e);
                            }
                        });
                        wait = Math.min(15000, 2000 + 1000L * (fails + 1));
                    }
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        });
        poller.start();
    }

    private void stopPolling() {
        polling = false;
        if (poller != null) poller.interrupt();
        poller = null;
    }

    private Result fetch() throws Exception {
        String base = serverUrl();
        if (base.length() == 0) throw new IOException("No server link set yet.");
        String url = base + (base.indexOf('?') >= 0 ? "&" : "?") + "api=state&n=" + station()
                + "&h=" + URLEncoder.encode(haveHash, "UTF-8");
        String body = new String(httpBytes(url), "UTF-8");

        JSONObject j;
        try {
            j = new JSONObject(body);
        } catch (Exception e) {
            if (body.contains("accounts.google.com") || body.contains("ServiceLogin")) {
                throw new IOException("The web app is asking for a sign-in. Redeploy it with access set to Anyone.");
            }
            throw new IOException("The server link didn't answer like the station web app. Check the link.");
        }
        if (j.has("error")) throw new IOException(j.optString("error"));

        Result r = new Result();
        r.json = j;
        r.at = System.currentTimeMillis();
        if (j.has("content")) {
            JSONObject c = j.getJSONObject("content");
            r.hasContent = true;
            r.type = c.optString("type", "text");
            if ("img".equals(r.type)) {
                try {
                    String src = c.optString("src", "");
                    byte[] data = src.startsWith("data:")
                            ? Base64.decode(src.substring(src.indexOf(',') + 1), Base64.DEFAULT)
                            : httpBytes(src);
                    r.bmp = decodeBitmap(data);
                } catch (Exception e) {
                    r.bmp = null;
                }
                if (r.bmp == null) {
                    r.type = "text";
                    r.text = "Couldn't load that image.";
                }
            } else {
                r.text = c.optString("text", "");
            }
        }
        return r;
    }

    private static byte[] httpBytes(String address) throws IOException {
        HttpURLConnection con = null;
        try {
            URL url = new URL(address);
            for (int hop = 0; hop < 5; hop++) {
                con = (HttpURLConnection) url.openConnection();
                con.setConnectTimeout(10000);
                con.setReadTimeout(20000);
                con.setInstanceFollowRedirects(false);
                con.setRequestProperty("User-Agent", "StationDisplay/1.0 (Android)");
                int code = con.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = con.getHeaderField("Location");
                    con.disconnect();
                    if (loc == null) throw new IOException("Bad redirect from server.");
                    url = new URL(url, loc);
                    continue;
                }
                if (code >= 400) throw new IOException("Server answered " + code + ".");
                InputStream in = con.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                return out.toByteArray();
            }
            throw new IOException("Too many redirects.");
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static Bitmap decodeBitmap(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        while (o.outWidth / sample > 2048 || o.outHeight / sample > 2048) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
    }

    private void onResult(Result r) {
        fails = 0;
        lastError = "";
        st = r.json;
        offset = st.optLong("now", r.at) - r.at;
        if (r.hasContent) {
            haveHash = st.optString("hash", "");
            if ("img".equals(r.type) && r.bmp != null) {
                showingImage = true;
                contentText = "";
                problemTv.setText("");
                problemTv.setVisibility(View.GONE);
                problemImg.setImageBitmap(r.bmp);
                problemImg.setVisibility(View.VISIBLE);
            } else {
                showingImage = false;
                contentText = r.text == null ? "" : r.text;
                problemImg.setImageDrawable(null);
                problemImg.setVisibility(View.GONE);
                problemTv.setText(contentText);
                problemTv.setVisibility(View.VISIBLE);
                fitText();
            }
        }
        if (st.has("brightness")) applyBrightness(st.optInt("brightness", 0));
        render();
    }

    private void onFail(Exception e) {
        fails++;
        String m = e.getMessage();
        lastError = (m == null || m.length() == 0) ? "Can't reach the server. Check Wi-Fi." : m;
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.ConnectException) {
            lastError = "No connection. Check Wi-Fi.";
        }
        render();
    }

    // ------------------------------------------------------------------ drawing

    private static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = (ms + 999) / 1000, m = s / 60;
        s = s % 60;
        return m + ":" + (s < 10 ? "0" : "") + s;
    }

    private void render() {
        if (root.getWidth() > 0 && badge.getTextSize() <= 0.5f) sizeChrome();
        ((GradientDrawable) dot.getBackground()).setColor(fails > 0 ? RED : GREEN);

        int n = station();
        String name = st == null ? "" : st.optString("name", "");
        boolean custom = name.length() > 0 && !name.equals("Station " + n);
        badge.setText(custom ? "Station " + n + " · " + name : "Station " + n);

        String status = st == null ? "" : st.optString("status", "idle");

        if (st == null || "idle".equals(status) || "done".equals(status) || (fails >= 3)) {
            idleLayout.setVisibility(View.VISIBLE);
            moveLayout.setVisibility(View.GONE);
            pausedTv.setVisibility(View.GONE);
            bigNum.setText(String.valueOf(n));
            bigName.setText(custom ? name : (st == null ? "" : st.optString("title", "")));
            if (lastError.length() > 0 && (st == null || fails >= 3)) {
                bigMsg.setTextColor(RED);
                bigMsg.setText(lastError);
            } else {
                bigMsg.setTextColor(Color.WHITE);
                bigMsg.setText(st == null ? "Connecting..." : "done".equals(status) ? "All done. Nice work!" : "Waiting to start");
            }
            return;
        }
        idleLayout.setVisibility(View.GONE);

        roundTv.setText("Round " + st.optInt("round", 1) + " of " + st.optInt("rounds", 1));

        long now = System.currentTimeMillis() + offset;
        long remain;
        if ("running".equals(status) || "moving".equals(status)) remain = st.optLong("endsAt", 0) - now;
        else if ("paused".equals(status)) remain = st.optLong("remainMs", 0);
        else remain = -1;

        long roundMs = st.optLong("roundSec", 0) * 1000;
        if ("waiting".equals(status)) {
            timerTv.setText("Next");
            timerTv.setTextColor(Color.WHITE);
        } else if ("moving".equals(status) || ("paused".equals(status) && "moving".equals(st.optString("pausedFrom")))) {
            timerTv.setText(fmt(roundMs));
            timerTv.setTextColor(Color.WHITE);
        } else {
            timerTv.setText(fmt(remain));
            timerTv.setTextColor(remain >= 0 && remain <= 30000 ? RED : Color.WHITE);
        }

        if ("moving".equals(status)) {
            moveLayout.setVisibility(View.VISIBLE);
            moveCount.setText(String.valueOf(Math.max(0, (remain + 999) / 1000)));
        } else {
            moveLayout.setVisibility(View.GONE);
        }
        pausedTv.setVisibility("paused".equals(status) ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ hidden settings

    private void askPin() {
        final EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setHint("PIN");
        new AlertDialog.Builder(this)
                .setTitle("Teacher settings")
                .setView(pin)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (BuildConfig.SETTINGS_PIN.equals(pin.getText().toString().trim())) showSettings(false);
                        else Toast.makeText(MainActivity.this, "Wrong PIN", Toast.LENGTH_SHORT).show();
                        hideSystemBars();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    private void showSettings(final boolean firstRun) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(6), dp(22), dp(10));

        box.addView(label("Which station is this tablet?"));
        final NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(1);
        picker.setMaxValue(30);
        picker.setValue(station());
        picker.setWrapSelectorWheel(false);
        box.addView(picker, new LinearLayout.LayoutParams(-2, -2));

        box.addView(label("Server link (your web app URL, ends in /exec)"));
        final EditText url = new EditText(this);
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(serverUrl());
        box.addView(url, new LinearLayout.LayoutParams(-1, -2));

        box.addView(label("Screen direction"));
        final RadioGroup orient = new RadioGroup(this);
        String[] opts = {"Landscape (wide)", "Portrait (tall)", "Turn with the tablet"};
        for (int i = 0; i < opts.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(opts[i]);
            rb.setId(1000 + i);
            orient.addView(rb);
        }
        orient.check(1000 + prefs.getInt(P_ORIENT, 0));
        box.addView(orient);

        box.addView(label("Tablet tools"));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button wifi = new Button(this);
        wifi.setText("Wi-Fi");
        wifi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                leaveLockTask();
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            }
        });
        Button sys = new Button(this);
        sys.setText("Android settings");
        sys.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                leaveLockTask();
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        row.addView(wifi);
        row.addView(sys);
        box.addView(row);

        if (isDeviceOwner()) {
            Button release = new Button(this);
            release.setText("Remove extra lockdown");
            release.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    removeDeviceOwner();
                }
            });
            box.addView(release);
        }

        TextView ver = new TextView(this);
        ver.setText("Station Display " + BuildConfig.VERSION_NAME + (isDeviceOwner() ? " · locked down" : ""));
        ver.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        ver.setPadding(0, dp(14), 0, 0);
        box.addView(ver);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(firstRun ? "Set up this tablet" : "Teacher settings")
                .setView(scroll)
                .setCancelable(!firstRun)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        prefs.edit()
                                .putInt(P_STATION, picker.getValue())
                                .putString(P_URL, url.getText().toString().trim())
                                .putInt(P_ORIENT, Math.max(0, orient.getCheckedRadioButtonId() - 1000))
                                .apply();
                        haveHash = "";
                        st = null;
                        contentText = "";
                        problemTv.setText("");
                        applyOrientation();
                        hideSystemBars();
                        render();
                    }
                });
        if (!firstRun) b.setNegativeButton("Cancel", null);
        b.show();
    }

    private void leaveLockTask() {
        try {
            stopLockTask();
        } catch (Exception ignored) {
        }
    }

    private void removeDeviceOwner() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            leaveLockTask();
            dpm.setStatusBarDisabled(admin(), false);
            dpm.setKeyguardDisabled(admin(), false);
            dpm.clearDeviceOwnerApp(getPackageName());
            Toast.makeText(this, "Extra lockdown removed.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't remove: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
