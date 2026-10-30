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
import java.io.File;
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
        heading.setText("AI ASSISTENT · LOCAL AGENT");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(15);
        panel.addView(heading);

        taskInput = new EditText(this);
        taskInput.setTextColor(Color.WHITE);
        taskInput.setHintTextColor(0xFFAAAAAA);
        taskInput.setHint("Beschreibe dein Ziel …");
        panel.addView(taskInput, new LinearLayout.LayoutParams(-1, dp(58)));

        status = new TextView(this);
        status.setTextColor(0xFFE0DDF5);
        status.setTextSize(12);
        status.setText("Offline-Modus. Bis zu 6 kontrollierte Schritte pro Auftrag.");
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
        if (task == null || task.trim().isEmpty()) { updateStatus("Bitte zuerst ein Ziel eingeben."); return; }
        if (ScreenCaptureService.awaitFrame(150) == null) {
            updateStatus("Keine Bildschirmfreigabe aktiv. Starte sie zuerst in der Haupt-App.");
            return;
        }
        File model = new File(getFilesDir(), "model.litertlm");
        if (!model.exists()) { updateStatus("Lokales Modell fehlt. Wähle zuerst eine .litertlm-Datei aus."); return; }
        running = true;
        stopRequested = false;
        worker.execute(() -> runAgent(task.trim(), model.getAbsolutePath()));
    }

    private void runAgent(String task, String modelPath) {
        int completed = 0;
        try {
            updateStatus("Lade lokales KI-Modell …");
            agent = new LocalVisionAgent(modelPath);
            for (int step = 1; step <= 6 && !stopRequested; step++) {
                String frame = ScreenCaptureService.awaitFrame(6000);
                if (frame == null) { updateStatus("Kein aktuelles Bildschirmbild erhalten. Agent pausiert."); break; }
                ScreenActionService service = ScreenActionService.instance;
                if (service == null) { updateStatus("Bedienungshilfe nicht aktiv. Agent gestoppt."); break; }
                String visibleText = service.readVisibleText();
                if (isSensitiveContext(visibleText)) {
                    updateStatus("Sicherheitsstopp: sensibler Bildschirm erkannt. Bitte selbst übernehmen.");
                    break;
                }

                updateStatus("Schritt " + step + "/6 · Lokale Bildanalyse …");
                String raw = agent.analyze(task, frame, visibleText, step, completed);
                JSONObject decision = parseDecision(raw);
                if (decision == null) { updateStatus("KI-Antwort war kein gültiges Aktionsformat. Gestoppt."); break; }
                String explanation = decision.optString("summary", "Nächsten Schritt geprüft.");
                if (decision.optBoolean("done", false)) {
                    updateStatus("Fertig: " + explanation);
                    break;
                }
                JSONObject action = decision.optJSONObject("action");
                if (action == null) { updateStatus("Keine sichere Aktion vorgeschlagen: " + explanation); break; }
                double confidence = action.optDouble("confidence", 0);
                if (confidence < 0.78) { updateStatus("Unsicherheit zu hoch – Agent pausiert: " + explanation); break; }

                boolean success = executeSafeAction(service, action);
                if (!success) { updateStatus("Aktion durch Sicherheitsprüfung blockiert oder nicht ausführbar."); break; }
                completed++;
                updateStatus("Aktion " + completed + " ausgeführt. Prüfe das neue Ergebnis …");
                Thread.sleep(900);
            }
            if (completed >= 6 && !stopRequested) updateStatus("Sicherheitslimit erreicht: maximal 6 Aktionen pro Auftrag.");
            else if (stopRequested) updateStatus("Agent manuell gestoppt.");
        } catch (Exception e) {
            updateStatus("Lokale KI konnte nicht starten: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (agent != null) { try { agent.close(); } catch (Exception ignored) {} agent = null; }
            running = false;
        }
    }

    private JSONObject parseDecision(String raw) {
        try {
            int first = raw.indexOf('{');
            int last = raw.lastIndexOf('}');
            if (first < 0 || last <= first) return null;
            return new JSONObject(raw.substring(first, last + 1));
        } catch (Exception e) { return null; }
    }

    private boolean executeSafeAction(ScreenActionService service, JSONObject action) throws Exception {
        String type = action.optString("type", "").toLowerCase(Locale.ROOT);
        if ("tap".equals(type)) {
            return service.safeTap((float)action.getDouble("x"), (float)action.getDouble("y"));
        }
        if ("swipe".equals(type)) {
            return service.safeSwipe((float)action.getDouble("x1"), (float)action.getDouble("y1"),
                    (float)action.getDouble("x2"), (float)action.getDouble("y2"), action.optLong("duration", 350));
        }
        if ("type".equals(type)) return service.safeType(action.getString("text"));
        return false;
    }

    private boolean isSensitiveContext(String text) {
        String s = text == null ? "" : text.toLowerCase(Locale.ROOT);
        String[] blocked = {"password", "passwort", "bank", "banking", "payment", "checkout", "kaufen",
                "purchase", "bestellen", "überweisen", "transfer", "login", "anmelden", "security",
                "sicherheit", "delete account", "konto löschen", "send", "senden", "publish",
                "veröffentlichen", "post", "teilen", "share", "subscription", "abonnement"};
        for (String word : blocked) if (s.contains(word)) return true;
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