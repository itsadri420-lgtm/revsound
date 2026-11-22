package com.meine.revsound;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {
    static final int BG = 0xFF0A0E1A, CARD = 0xFF131A2E, CYAN = 0xFF00E5FF, TXT = 0xFFE8F0FF,
            DIM = 0xFF8A97B8, RED = 0xFFFF5252, GREEN = 0xFF4DFF88;
    static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    float dp;
    SharedPreferences sp;
    BluetoothAdapter bt;
    BluetoothSocket sock;
    InputStream in;
    OutputStream out;
    volatile boolean connected = false, running = false;
    Thread obdThread;
    MediaPlayer mp;
    Uri soundUri;
    int baseRpm = 900, maxRpm = 7000, curRpm = 0;
    float volume = 1f;
    Handler h = new Handler(Looper.getMainLooper());

    TextView status, rpmText, soundName;
    Gauge gauge;
    Button btConnect, btSound, btEngine;

    int d(int v) {
        return (int) (v * dp);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        sp = getSharedPreferences("revsound", MODE_PRIVATE);
        bt = BluetoothAdapter.getDefaultAdapter();
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        String savedUri = sp.getString("sound", null);
        if (savedUri != null) soundUri = Uri.parse(savedUri);
        baseRpm = sp.getInt("base", 900);
        maxRpm = sp.getInt("max", 7000);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(d(18), d(44), d(18), d(28));

        TextView title = text("RevSound", 28, CYAN, true);
        title.setLetterSpacing(0.08f);
        col.addView(title);
        col.addView(text("OBD2-Drehzahl → Motorsound", 13, DIM, false), lp(4));

        LinearLayout c1 = card();
        c1.addView(text("1. OBD2-Adapter", 15, TXT, true));
        c1.addView(text("Zuerst im Android-System mit deinem ELM327-Adapter koppeln (Einstellungen → Bluetooth). Dann hier auswählen.", 12, DIM, false), lp(6));
        btConnect = btn("Adapter wählen & verbinden", true);
        c1.addView(btConnect, lp(10));
        status = text("Nicht verbunden", 13, DIM, false);
        c1.addView(status, lp(8));
        col.addView(c1, lp(16));

        LinearLayout c2 = card();
        c2.addView(text("2. Sound", 15, TXT, true));
        c2.addView(text("Eigene Aufnahme oder lizenzfreien Sound als Leerlauf-Loop wählen (mp3/wav).", 12, DIM, false), lp(6));
        btSound = btn("Sound wählen", false);
        c2.addView(btSound, lp(10));
        soundName = text(soundUri != null ? "Gewählt: " + nameOf(soundUri) : "Kein Sound gewählt", 12, GREEN, false);
        c2.addView(soundName, lp(6));

        LinearLayout brow = new LinearLayout(this);
        brow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams half1 = new LinearLayout.LayoutParams(0, -2, 1f);
        half1.rightMargin = d(5);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, -2, 1f);
        half2.leftMargin = d(5);
        LinearLayout bcol1 = new LinearLayout(this);
        bcol1.setOrientation(LinearLayout.VERTICAL);
        bcol1.addView(text("Leerlauf-RPM der Aufnahme", 11, DIM, false));
        TextView baseVal = text(baseRpm + " U/min", 15, TXT, true);
        bcol1.addView(baseVal);
        SeekBar baseBar = new SeekBar(this);
        baseBar.setMax(2500);
        baseBar.setProgress(baseRpm - 500);
        bcol1.addView(baseBar);
        LinearLayout bcol2 = new LinearLayout(this);
        bcol2.setOrientation(LinearLayout.VERTICAL);
        bcol2.addView(text("Max-Drehzahl (Redline)", 11, DIM, false));
        TextView maxVal = text(maxRpm + " U/min", 15, TXT, true);
        bcol2.addView(maxVal);
        SeekBar maxBar = new SeekBar(this);
        maxBar.setMax(6000);
        maxBar.setProgress(maxRpm - 4000);
        bcol2.addView(maxBar);
        brow.addView(bcol1, half1);
        brow.addView(bcol2, half2);
        c2.addView(brow, lp(12));
        baseBar.setOnSeekBarChangeListener(seek(v -> {
            baseRpm = 500 + v;
            baseVal.setText(baseRpm + " U/min");
            sp.edit().putInt("base", baseRpm).apply();
        }));
        maxBar.setOnSeekBarChangeListener(seek(v -> {
            maxRpm = 4000 + v;
            maxVal.setText(maxRpm + " U/min");
            sp.edit().putInt("max", maxRpm).apply();
        }));
        col.addView(c2, lp(14));

        LinearLayout c3 = card();
        c3.addView(text("3. Lautstärke", 15, TXT, true));
        SeekBar volBar = new SeekBar(this);
        volBar.setMax(100);
        volBar.setProgress(100);
        c3.addView(volBar, lp(8));
        volBar.setOnSeekBarChangeListener(seek(v -> {
            volume = v / 100f;
            if (mp != null) mp.setVolume(volume, volume);
        }));
        col.addView(c3, lp(14));

        LinearLayout c4 = card();
        c4.setGravity(Gravity.CENTER);
        gauge = new Gauge(this);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, d(160));
        c4.addView(gauge, glp);
        rpmText = text("0 U/min", 26, CYAN, true);
        rpmText.setGravity(Gravity.CENTER);
        c4.addView(rpmText, lp(4));
        btEngine = btn("▶ Sound starten", true);
        c4.addView(btEngine, lp(14));
        col.addView(c4, lp(16));

        sv.addView(col);
        setContentView(sv);

        btConnect.setOnClickListener(v -> pickDevice());
        btSound.setOnClickListener(v -> pickSound());
        btEngine.setOnClickListener(v -> toggleEngine());
    }

    SeekBar.OnSeekBarChangeListener seek(java.util.function.IntConsumer f) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                f.accept(p);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        };
    }

    TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    GradientDrawable boxBg(int fill, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(d(radius));
        if (stroke != 0) g.setStroke(d(1), stroke);
        return g;
    }

    Button btn(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        if (primary) {
            b.setTextColor(0xFF07101F);
            b.setBackground(boxBg(CYAN, 0, 12));
        } else {
            b.setTextColor(CYAN);
            b.setBackground(boxBg(0x2200E5FF, CYAN, 12));
        }
        return b;
    }

    LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.topMargin = d(top);
        return l;
    }

    LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(boxBg(CARD, 0x3300E5FF, 14));
        c.setPadding(d(16), d(14), d(16), d(14));
        return c;
    }

    boolean hasPerm() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    void pickDevice() {
        if (bt == null) {
            status.setText("Dieses Gerät hat kein Bluetooth");
            return;
        }
        if (!hasPerm()) {
            requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT,
                    android.Manifest.permission.BLUETOOTH_SCAN}, 5);
            return;
        }
        Set<BluetoothDevice> paired;
        try {
            paired = bt.getBondedDevices();
        } catch (SecurityException e) {
            return;
        }
        if (paired.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Kein gekoppeltes Gerät")
                    .setMessage("Kopple deinen OBD2-Adapter zuerst in den Android-Bluetooth-Einstellungen, dann komm hierher zurück.")
                    .setPositiveButton("Bluetooth-Einstellungen öffnen", (d, w) ->
                            startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)))
                    .setNegativeButton("Abbrechen", null)
                    .show();
            return;
        }
        List<BluetoothDevice> list = new ArrayList<>(paired);
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            try {
                names[i] = list.get(i).getName() + "  (" + list.get(i).getAddress() + ")";
            } catch (SecurityException e) {
                names[i] = list.get(i).getAddress();
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("OBD2-Adapter wählen")
                .setItems(names, (d, which) -> connectTo(list.get(which)))
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int rq, String[] p, int[] g) {
        super.onRequestPermissionsResult(rq, p, g);
        if (rq == 5 && g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) pickDevice();
    }

    void connectTo(BluetoothDevice dev) {
        status.setText("Verbinde…");
        new Thread(() -> {
            try {
                sock = dev.createRfcommSocketToServiceRecord(SPP);
                bt.cancelDiscovery();
                sock.connect();
                in = sock.getInputStream();
                out = sock.getOutputStream();
                sendCmd("ATZ");
                sleep(300);
                sendCmd("ATE0");
                sendCmd("ATL0");
                sendCmd("ATSP0");
                connected = true;
                h.post(() -> {
                    status.setText("Verbunden ✓  Drehzahl wird ausgelesen…");
                    startObdLoop();
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                h.post(() -> status.setText("Verbindung fehlgeschlagen: " + msg));
            }
        }).start();
    }

    void sendCmd(String c) throws IOException {
        out.write((c + "\r").getBytes());
        out.flush();
    }

    void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    String readResp() {
        try {
            StringBuilder sb = new StringBuilder();
            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < 1500) {
                if (in.available() > 0) {
                    int c = in.read();
                    if (c == '>') break;
                    sb.append((char) c);
                } else {
                    sleep(10);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    void startObdLoop() {
        running = true;
        obdThread = new Thread(() -> {
            while (running && connected) {
                try {
                    sendCmd("010C");
                    String resp = readResp().replaceAll("[\r\n]", " ").trim();
                    int rpm = parseRpm(resp);
                    if (rpm >= 0) {
                        final int r = rpm;
                        h.post(() -> onRpm(r));
                    }
                } catch (Exception e) {
                    connected = false;
                    h.post(() -> status.setText("Verbindung verloren"));
                }
                sleep(120);
            }
        });
        obdThread.start();
    }

    int parseRpm(String resp) {
        String clean = resp.replace(" ", "").toUpperCase(Locale.US);
        int i = clean.indexOf("410C");
        if (i < 0 || clean.length() < i + 8) return -1;
        try {
            int a = Integer.parseInt(clean.substring(i + 4, i + 6), 16);
            int b = Integer.parseInt(clean.substring(i + 6, i + 8), 16);
            return ((a * 256) + b) / 4;
        } catch (Exception e) {
            return -1;
        }
    }

    void onRpm(int rpm) {
        curRpm = rpm;
        rpmText.setText(rpm + " U/min");
        gauge.setRpm(rpm, maxRpm);
        gauge.invalidate();
        if (mp != null && running) {
            float ratio = baseRpm > 0 ? (float) rpm / baseRpm : 1f;
            ratio = Math.max(0.4f, Math.min(3.0f, ratio));
            try {
                mp.setPlaybackParams(new PlaybackParams().setSpeed(ratio).setPitch(ratio));
            } catch (Exception ignored) {
            }
        }
    }

    void pickSound() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        startActivityForResult(i, 1);
    }

    @Override
    protected void onActivityResult(int rq, int rs, Intent data) {
        super.onActivityResult(rq, rs, data);
        if (rq == 1 && rs == RESULT_OK && data != null && data.getData() != null) {
            soundUri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(soundUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            sp.edit().putString("sound", soundUri.toString()).apply();
            soundName.setText("Gewählt: " + nameOf(soundUri));
        }
    }

    String nameOf(Uri u) {
        try (android.database.Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        return "Datei";
    }

    void toggleEngine() {
        if (running && mp != null) {
            mp.stop();
            mp.release();
            mp = null;
            btEngine.setText("▶ Sound starten");
            return;
        }
        if (soundUri == null) {
            status.setText("Bitte zuerst einen Sound wählen");
            return;
        }
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setDataSource(this, soundUri);
            mp.setLooping(true);
            mp.setVolume(volume, volume);
            mp.prepare();
            mp.start();
            running = true;
            btEngine.setText("■ Sound stoppen");
        } catch (Exception e) {
            status.setText("Sound-Fehler: " + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        connected = false;
        try {
            if (sock != null) sock.close();
        } catch (Exception ignored) {
        }
        if (mp != null) {
            mp.release();
            mp = null;
        }
    }

    static class Gauge extends View {
        int rpm = 0, max = 7000;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Gauge(Context c) {
            super(c);
        }

        void setRpm(int r, int m) {
            rpm = r;
            max = m;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2, cy = h * 0.95f, r = Math.min(w / 2.1f, h * 0.9f);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(18);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0xFF1E2A4A);
            c.drawArc(cx - r, cy - r, cx + r, cy + r, 180, 180, false, p);
            float frac = Math.max(0f, Math.min(1f, (float) rpm / max));
            p.setColor(frac > 0.85f ? Color.RED : Color.rgb(0, 229, 255));
            c.drawArc(cx - r, cy - r, cx + r, cy + r, 180, 180 * frac, false, p);
        }
    }
}
