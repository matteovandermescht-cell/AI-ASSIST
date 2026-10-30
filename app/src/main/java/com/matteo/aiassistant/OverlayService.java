package com.matteo.aiassistant;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.*;
import android.graphics.Color;
import org.json.JSONObject;
import java.util.Locale;
import java.util.concurrent.*;

public class OverlayService extends Service {
    private WindowManager wm;
    private TextView bubble, status;
    private LinearLayout panel;
    private EditText taskInput;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean running = false;
    private volatile boolean stopRequested = false;
    private LocalVisionAgent agent;

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density); }
    private WindowManager.LayoutParams params(int w, int h) {
        return new WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
    }

    @Override public void onCreate() {
        super.onCreate();
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return; }
        wm = (WindowManager)getSystemService(WINDOW_SERVICE);
        bubble = new TextView(this);
        bubble.setText("AI");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(16);
        bubble.setGravity(Gravity.CENTER);
        bubble.setBackgroundColor(0xFF6750D8);
        bubble.setOnClickListener(v -> togglePanel());
        WindowManager.LayoutParams p = params(dp(58), dp(58));
        p.gravity = Gravity.TOP | Gravity.END;
        p.x = dp(14); p.y = dp(180);
        wm.addView(bubble, p);
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private void togglePanel() {
        if (panel != null) { wm.removeView(panel); panel = null; return; }
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));
        panel.setBackgroundColor(0xF01B1B29);

        TextView heading = new TextView(this);
        heading.setText("AI ASSISTENT · VISUAL ONLY");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(15);
        panel.addView(heading);

        taskInput = new EditText(this);
        taskInput.setTextColor(Color.WHITE);
        taskInput.setHintTextColor(0xFFAAAAAA);
        taskInput.setHint("z. B. „Drücke Start“ oder „Tap Play“");
        panel.addView(taskInput, new LinearLayout.LayoutParams(-1, dp(58)));

        status = new TextView(this);
        status.setTextColor(0xFFE0DDF5);
        status.setTextSize(12);
        status.setText("Nur Bildschirmbild · lokale Objekterkennung + OCR · kein Sprachmodell.");
        panel.addView(status);

        Button start = makeButton("Agent starten");
        panel.addView(start);
        start.setOnClickListener(v -> startAgent(taskInput.getText().toString()));

        Button stop = makeButton("STOP · Agent sofort anhalten");
        stop.setTextColor(0xFFFF7777);
        panel.addView(stop);
        stop.setOnClickListener(v -> requestStop());

        Button close = makeButton("Overlay schließen");
        panel.addView(close);
        close.setOnClickListener(v -> stopSelf());

        WindowManager.LayoutParams p = params(dp(320), -2);
        p.gravity = Gravity.TOP | Gravity.END;
        p.x = dp(10); p.y = dp(248);
        p.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        wm.addView(panel, p);
    }

    private void startAgent(String task) {
        if (running) { updateStatus("Der Agent läuft bereits."); return; }
        if (task == null || task.trim().isEmpty()) { updateStatus("Bitte ein konkretes Ziel eingeben."); return; }
        if (ScreenCaptureService.latestFramePath() == null) {
            updateStatus("Kein aktuelles Bildschirmbild. Bildschirmfreigabe in der Haupt-App starten.");
            return;
        }
        if (ScreenActionService.instance == null) {
            updateStatus("Bedienungshilfe nicht aktiv. Agent kann nicht sicher tippen.");
            return;
        }
        running = true;
        stopRequested = false;
        worker.execute(() -> runAgent(task.trim()));
    }

    private void runAgent(String task) {
        int completed = 0;
        try {
            updateStatus("Initialisiere lokale visuelle Erkennung …");
            agent = new LocalVisionAgent(getApplicationContext());
            long lastFrameVersion = ScreenCaptureService.currentFrameVersion();
            for (int step = 1; step <= 12 && !stopRequested; step++) {
                String frame = ScreenCaptureService.awaitNextFramePath(lastFrameVersion, 4000);
                if (frame == null) { updateStatus("Kein neues Bildschirmbild erhalten. Agent pausiert."); break; }
                lastFrameVersion = ScreenCaptureService.currentFrameVersion();
                ScreenActionService service = ScreenActionService.instance;
                if (service == null) { updateStatus("Bedienungshilfe nicht aktiv. Agent gestoppt."); break; }

                updateStatus("Visuelle Analyse · Schritt " + step + "/12 …");
                String raw = agent.analyze(task, frame, "", step, completed);
                if (stopRequested) { updateStatus("Agent gestoppt – keine weitere Aktion ausgeführt."); break; }
                JSONObject decision = parseDecision(raw);
                if (decision == null) { updateStatus("Ungültige Analyseantwort. Agent sicher gestoppt."); break; }
                String explanation = decision.optString("summary", "Bild geprüft.");
                if (decision.optBoolean("done", false)) {
                    updateStatus("Fertig: " + explanation);
                    break;
                }
                JSONObject action = decision.optJSONObject("action");
                if (action == null) { updateStatus(explanation); break; }
                double confidence = action.optDouble("confidence", 0);
                if (confidence < 0.78) { updateStatus("Ziel nicht sicher genug erkannt – Agent pausiert."); break; }

                boolean success = executeSafeAction(service, action);
                if (!success) { updateStatus("Aktion durch Sicherheitsprüfung blockiert oder nicht ausführbar."); break; }
                completed++;
                updateStatus("Aktion " + completed + " ausgeführt · prüfe den neuen Bildschirm …");
            }
            if (completed >= 12 && !stopRequested) updateStatus("Sicherheitslimit erreicht: 12 Aktionen pro Auftrag.");
            else if (stopRequested) updateStatus("Agent manuell gestoppt.");
        } catch (Exception e) {
            updateStatus("Visuelle Erkennung fehlgeschlagen: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (agent != null) { try { agent.close(); } catch (Exception ignored) {} agent = null; }
            running = false;
        }
    }

    private JSONObject parseDecision(String raw) {
        try { return new JSONObject(raw); }
        catch (Exception e) { return null; }
    }

    private boolean executeSafeAction(ScreenActionService service, JSONObject action) throws Exception {
        String type = action.optString("type", "").toLowerCase(Locale.ROOT);
        if ("tap".equals(type)) {
            return service.safeTap((float)action.getDouble("x"), (float)action.getDouble("y"));
        }
        return false;
    }

    private void requestStop() {
        stopRequested = true;
        updateStatus("Stopp angefordert …");
    }

    private void updateStatus(String message) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (status != null) status.setText(message);
        });
    }

    @Override public void onDestroy() {
        stopRequested = true;
        if (bubble != null) wm.removeView(bubble);
        if (panel != null) wm.removeView(panel);
        worker.shutdownNow();
        if (agent != null) { try { agent.close(); } catch (Exception ignored) {} }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
