package com.matteo.aiassistant;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;
import java.io.*;

public class MainActivity extends Activity {
    private static final int PICK_MODEL = 41;
    private static final int CAPTURE_PERMISSION = 42;
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density); }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF29283A));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52));
        p.topMargin = dp(9);
        b.setLayoutParams(p);
        return b;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF10111A);
        getWindow().setNavigationBarColor(0xFF10111A);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(20));
        root.setBackgroundColor(0xFF10111A);

        TextView title = new TextView(this);
        title.setText("AI ASSISTENT 2.0");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(26);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Lokaler visueller Android-Agent · keine API-Schlüssel");
        subtitle.setTextColor(0xFFAAAABD);
        subtitle.setPadding(0, dp(6), 0, dp(18));
        root.addView(subtitle);

        File model = new File(getFilesDir(), "model.litertlm");
        TextView modelStatus = new TextView(this);
        modelStatus.setText(model.exists() ? "Lokales Modell: bereit" : "Noch kein lokales Vision-Modell ausgewählt");
        modelStatus.setTextColor(model.exists() ? 0xFF8FE0A0 : 0xFFFFC66D);
        root.addView(modelStatus);

        Button choose = makeButton("1 · Lokales .litertlm-Modell auswählen");
        root.addView(choose);
        choose.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.setType("*/*");
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(pick, PICK_MODEL);
        });

        TextView hint = new TextView(this);
        hint.setText("Wähle ein multimodales LiteRT-LM-Modell mit Bild-/Vision-Unterstützung. Das Modell bleibt auf dem Gerät. Der Modell-Download ist nicht Teil der APK.");
        hint.setTextColor(0xFF9999AA);
        hint.setTextSize(12);
        hint.setPadding(0, dp(8), 0, dp(10));
        root.addView(hint);

        Button accessibility = makeButton("2 · Bedienungshilfe aktivieren");
        root.addView(accessibility);
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        Button overlay = makeButton("3 · Overlay-Berechtigung");
        root.addView(overlay);
        overlay.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            } else Toast.makeText(this, "Overlay ist bereits erlaubt", Toast.LENGTH_SHORT).show();
        });

        Button start = makeButton("4 · Bildschirmfreigabe starten");
        start.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF6750D8));
        root.addView(start);
        start.setOnClickListener(v -> beginCapture());

        Button stop = makeButton("Agent und Bildschirmfreigabe stoppen");
        root.addView(stop);
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, OverlayService.class));
            stopService(new Intent(this, ScreenCaptureService.class));
            Toast.makeText(this, "Agent gestoppt", Toast.LENGTH_SHORT).show();
        });

        TextView safety = new TextView(this);
        safety.setText("Sicherheitsgrenzen: maximal 6 Aktionen pro Auftrag, begrenzte Aktionen, Stopp-Schaltfläche und Sperre sensibler Bildschirmbereiche. Überwache den Agenten.");
        safety.setTextColor(0xFF9999AA);
        safety.setTextSize(12);
        safety.setPadding(0, dp(18), 0, 0);
        root.addView(safety);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void beginCapture() {
        if (!new File(getFilesDir(), "model.litertlm").exists()) {
            Toast.makeText(this, "Bitte zuerst ein lokales Vision-Modell auswählen.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Bitte zuerst die Overlay-Berechtigung erteilen.", Toast.LENGTH_LONG).show();
            return;
        }
        MediaProjectionManager manager = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), CAPTURE_PERMISSION);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_MODEL && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            try {
                File destination = new File(getFilesDir(), "model.litertlm");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(destination)) {
                    byte[] buffer = new byte[1024 * 1024];
                    int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                }
                Toast.makeText(this, "Modell lokal gespeichert: " + (destination.length() / (1024 * 1024)) + " MB", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Modell konnte nicht kopiert werden: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == CAPTURE_PERMISSION && resultCode == RESULT_OK && data != null) {
            Intent capture = new Intent(this, ScreenCaptureService.class);
            capture.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
            capture.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(capture); else startService(capture);
            startService(new Intent(this, OverlayService.class));
            Toast.makeText(this, "Bildschirmfreigabe aktiv. Öffne das AI-Overlay.", Toast.LENGTH_LONG).show();
            finish();
        }
    }
}