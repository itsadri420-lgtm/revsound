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
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
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
import java.util.Random;
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
    Button btConnect, btSound, btEngine, tabObd, tabSim;
    FrameLayout root;
    ScrollView pageObd, pageSim;

    // Simulation
    float simRpm = 0, simTarget = 0;
    boolean simPedal = false;
    Handler simH = new Handler(Looper.getMainLooper());
    Runnable simLoop;
    CarView carView;
    Gauge simGauge;
    TextView simRpmText;
    PopEngine pop;

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
        pop = new PopEngine();

        String savedUri = sp.getString("sound", null);
        if (savedUri != null) soundUri = Uri.parse(savedUri);
        baseRpm = sp.getInt("base", 900);
        maxRpm = sp.getInt("max", 7000);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(d(18), d(44), d(18), 0);
        tabObd = tabBtn("OBD-Modus", true);
        tabSim = tabBtn("Simulation", false);
        addW(tabs, tabObd);
        addW(tabs, tabSim);

        pageObd = buildObdPage();
        pageSim = buildSimPage();

        FrameLayout content = new FrameLayout(this);
        content.addView(pageObd);
        content.addView(pageSim);
        pageSim.setVisibility(View.GONE);

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
        outer.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(outer, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        tabObd.setOnClickListener(v -> switchTab(true));
        tabSim.setOnClickListener(v -> switchTab(false));
    }

    void switchTab(boolean obd) {
        pageObd.setVisibility(obd ? View.VISIBLE : View.GONE);
        pageSim.setVisibility(obd ? View.GONE : View.VISIBLE);
        stylizeTab(tabObd, obd);
        stylizeTab(tabSim, !obd);
        if (!obd) startSimLoop();
        else stopSimLoop();
    }

    Button tabBtn(String s, boolean on) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        stylizeTab(b, on);
        return b;
    }

    void stylizeTab(Button b, boolean on) {
        if (on) {
            b.setTextColor(0xFF07101F);
            b.setBackground(boxBg(CYAN, 0, 12));
        } else {
            b.setTextColor(DIM);
            b.setBackground(boxBg(0x22FFFFFF, 0x33555577, 12));
        }
    }

    // ---------- OBD-Seite ----------
    ScrollView buildObdPage() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(d(18), d(14), d(18), d(28));

        TextView title = text("RevSound", 26, CYAN, true);
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
        c2.addView(text("Eigene Aufnahme oder lizenzfreien Sound als Leerlauf-Loop wählen (mp3/wav). Gilt für OBD-Modus und Simulation.", 12, DIM, false), lp(6));
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

        btConnect.setOnClickListener(v -> pickDevice());
        btSound.setOnClickListener(v -> pickSound());
        btEngine.setOnClickListener(v -> toggleEngine());
        return sv;
    }

    // ---------- Simulations-Seite ----------
    ScrollView buildSimPage() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(d(18), d(14), d(18), d(28));

        col.addView(text("Simulation", 24, CYAN, true));
        col.addView(text("Teste den Sound ohne Auto – mit virtuellem Gaspedal", 13, DIM, false), lp(4));

        LinearLayout cc = card();
        carView = new CarView(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, d(140));
        cc.addView(carView, clp);
        col.addView(cc, lp(14));

        LinearLayout gc = card();
        gc.setGravity(Gravity.CENTER);
        simGauge = new Gauge(this);
        LinearLayout.LayoutParams gglp = new LinearLayout.LayoutParams(-1, d(150));
        gc.addView(simGauge, gglp);
        simRpmText = text("0 U/min", 24, CYAN, true);
        simRpmText.setGravity(Gravity.CENTER);
        gc.addView(simRpmText, lp(4));
        col.addView(gc, lp(14));

        LinearLayout pc = card();
        pc.addView(text("Gaspedal (gedrückt halten)", 13, DIM, false));
        PedalView pedal = new PedalView(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, d(110));
        plp.topMargin = d(8);
        pc.addView(pedal, plp);
        col.addView(pc, lp(14));

        Button popBtn = btn("💥 Pop-Sound jetzt auslösen", false);
        col.addView(popBtn, lp(10));
        popBtn.setOnClickListener(v -> pop.playPop(curSimSpeed()));

        col.addView(text("Tipp: Lass das Gaspedal bei hoher Drehzahl schlagartig los, dann kommen automatisch Pop-Sounds.", 12, DIM, false), lp(10));

        sv.addView(col);
        return sv;
    }

    float curSimSpeed() {
        return Math.max(0.3f, Math.min(2f, simRpm / Math.max(1, baseRpm)));
    }

    // ---------- Simulations-Logik ----------
    void startSimLoop() {
        if (soundUri != null && mp == null) startEngineSoundOnly();
        if (simLoop != null) return;
        simLoop = new Runnable() {
            @Override
            public void run() {
                float dt = 0.05f;
                if (simPedal) {
                    simTarget = Math.min(maxRpm, simTarget + (maxRpm - baseRpm) * dt * 1.8f);
                } else {
                    simTarget = Math.max(baseRpm, simTarget - (maxRpm - baseRpm) * dt * 2.6f);
                }
                float prev = simRpm;
                simRpm += (simTarget - simRpm) * 0.25f;
                if (prev - simRpm > (maxRpm - baseRpm) * 0.12f && prev > baseRpm + (maxRpm - baseRpm) * 0.35f) {
                    pop.playPop(curSimSpeed());
                }
                simRpmText.setText((int) simRpm + " U/min");
                simGauge.setRpm((int) simRpm, maxRpm);
                simGauge.invalidate();
                carView.setIntensity((simRpm - baseRpm) / Math.max(1, maxRpm - baseRpm));
                carView.invalidate();
                if (mp != null) {
                    float ratio = baseRpm > 0 ? simRpm / baseRpm : 1f;
                    ratio = Math.max(0.4f, Math.min(3.0f, ratio));
                    try {
                        mp.setPlaybackParams(new PlaybackParams().setSpeed(ratio).setPitch(ratio));
                    } catch (Exception ignored) {
                    }
                }
                simH.postDelayed(this, 50);
            }
        };
        simRpm = baseRpm;
        simTarget = baseRpm;
        simH.post(simLoop);
    }

    void stopSimLoop() {
        if (simLoop != null) {
            simH.removeCallbacks(simLoop);
            simLoop = null;
        }
    }

    void startEngineSoundOnly() {
        if (soundUri == null) return;
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
        } catch (Exception ignored) {
        }
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

    void addW(LinearLayout r, View b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        p.setMargins(d(3), 0, d(3), 0);
        r.addView(b, p);
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
            running = false;
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
        stopSimLoop();
        try {
            if (sock != null) sock.close();
        } catch (Exception ignored) {
        }
        if (mp != null) {
            mp.release();
            mp = null;
        }
    }

    // ---------- Drehzahlmesser ----------
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

    // ---------- Gaspedal ----------
    class PedalView extends View {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean pressed = false;

        PedalView(Context c) {
            super(c);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                pressed = true;
                simPedal = true;
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                pressed = false;
                simPedal = false;
            }
            invalidate();
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL);
            p.setColor(pressed ? 0xFF1E6B3A : 0xFF1A2440);
            c.drawRoundRect(0, 0, w, h, d(16), d(16), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3);
            p.setColor(pressed ? GREEN : 0x5500E5FF);
            c.drawRoundRect(2, 2, w - 2, h - 2, d(16), d(16), p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(TXT);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(18 * dp);
            c.drawText(pressed ? "GAS!" : "Gaspedal", w / 2, h / 2 + 6 * dp, p);
        }
    }

    // ---------- Auto-Silhouette ----------
    static class CarView extends View {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float intensity = 0;
        Random r = new Random();
        float shake = 0;

        CarView(Context c) {
            super(c);
        }

        void setIntensity(float i) {
            intensity = Math.max(0f, Math.min(1f, i));
            shake = (r.nextFloat() - 0.5f) * intensity * 4f;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float cy = h * 0.72f + shake;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF070A14);
            c.drawRect(0, 0, w, h, p);
            Path body = new Path();
            float bx = w * 0.1f, bw = w * 0.8f, bh = h * 0.28f;
            body.moveTo(bx, cy);
            body.lineTo(bx + bw * 0.1f, cy - bh);
            body.lineTo(bx + bw * 0.35f, cy - bh * 1.6f);
            body.lineTo(bx + bw * 0.65f, cy - bh * 1.6f);
            body.lineTo(bx + bw * 0.9f, cy - bh);
            body.lineTo(bx + bw, cy);
            body.close();
            int glow = (int) (60 + intensity * 180);
            p.setColor(Color.rgb(20, 40 + glow / 3, 60 + glow / 2));
            c.drawPath(body, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3);
            p.setColor(Color.argb(255, 0, 180 + (int) (intensity * 75), 255));
            c.drawPath(body, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF0A0E1A);
            c.drawCircle(bx + bw * 0.22f, cy, bh * 0.45f, p);
            c.drawCircle(bx + bw * 0.78f, cy, bh * 0.45f, p);
            if (intensity > 0.05f) {
                p.setColor(Color.argb((int) (150 * intensity), 255, 120, 0));
                c.drawCircle(bx + bw + 6 * intensity * 10, cy - bh * 0.3f, 4 + intensity * 8, p);
            }
        }
    }

    // ---------- Pop-Sound (synthetisch) ----------
    static class PopEngine {
        void playPop(float pitch) {
            new Thread(() -> {
                try {
                    int sr = 22050;
                    int n = (int) (sr * 0.18f);
                    short[] buf = new short[n];
                    Random r = new Random();
                    float decay = 7f;
                    for (int i = 0; i < n; i++) {
                        float t = i / (float) sr;
                        float env = (float) Math.exp(-decay * t);
                        float noise = (r.nextFloat() * 2 - 1);
                        float tone = (float) Math.sin(2 * Math.PI * (90 * pitch) * t);
                        float s = (noise * 0.6f + tone * 0.4f) * env;
                        buf[i] = (short) Math.max(-32000, Math.min(32000, s * 28000));
                    }
                    AudioTrack at = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build())
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(sr)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .build())
                            .setBufferSizeInBytes(buf.length * 2)
                            .setTransferMode(AudioTrack.MODE_STATIC)
                            .build();
                    at.write(buf, 0, buf.length);
                    at.play();
                    Thread.sleep(220);
                    at.release();
                } catch (Exception ignored) {
                }
            }).start();
        }
    }
}
