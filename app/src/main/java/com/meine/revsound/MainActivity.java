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
        c1.addView(text("Zuerst im Android-System mit deinem ELM327-Adap
