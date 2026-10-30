package com.matteo.aiassistant;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;

public class MainActivity extends Activity {
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
        title.setText("AI ASSISTENT 3.0 · VISUAL");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(25);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Rein visuelle lokale Android-KI · ohne Sprachmodell und API-Schlüssel");
        subtitle.setTextColor(0xFFAAAABD);
        subtitle.setPadding(0, dp(6), 0, dp(18));
        root.addView(subtitle);

        TextView modelStatus = new TextView(this);
        modelStatus.setText("Erkennung: ML Kit Objekterkennung + Texterkennung");
        modelStatus.setTextColor(0xFF8FE0A0);
        root.addView(modelStatus);

        TextView hint = new TextView(this);
        hint.setText("Die Analyse nutzt ausschließlich aufgenommene Bildschirmbilder. Objekte werden mit Begrenzungsrahmen erkannt; sichtbarer Text wird per OCR gelesen. In dieser Version tippt der Agent nur auf ein ausdrücklich genanntes, im Bild erkanntes Textziel. Die erste Objekterkennung kann eine Internetverbindung benötigen, um ihr lokales Modell bereitzustellen.");
        hint.setTextColor(0xFF9999AA);
        hint.setTextSize(12);
        hint.setPadding(0, dp(8), 0, dp(10));
        root.addView(hint);

        Button accessibility = makeButton("1 · Bedienungshilfe aktivieren");
        root.addView(accessibility);
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        Button overlay = makeButton("2 · Overlay-Berechtigung");
        root.addView(overlay);
        overlay.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            } else Toast.makeText(this, "Overlay ist bereits erlaubt", Toast.LENGTH_SHORT).show();
        });

        Button start = makeButton("3 · Bildschirmfreigabe starten");
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
        safety.setText("Sicherheitsgrenzen: maximal 12 Schritte pro Auftrag, nur visuell erkannte Textziele, zusätzliche Klickprüfung, Stopp-Schaltfläche. Überwache den Agenten.");
        safety.setTextColor(0xFF9999AA);
        safety.setTextSize(12);
        safety.setPadding(0, dp(18), 0, 0);
        root.addView(safety);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void beginCapture() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Bitte zuerst die Overlay-Berechtigung erteilen.", Toast.LENGTH_LONG).show();
            return;
        }
        MediaProjectionManager manager = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), 42);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 42 && resultCode == RESULT_OK && data != null) {
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
