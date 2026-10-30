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
    private WindowManager wm; private TextView bubble,status; private LinearLayout panel; private EditText taskInput;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile boolean running=false,stopRequested=false; private LocalVisionAgent agent;
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density);}
    private WindowManager.LayoutParams params(int w,int h){return new WindowManager.LayoutParams(w,h,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);}
    @Override public void onCreate(){
        super.onCreate();if(!Settings.canDrawOverlays(this)){stopSelf();return;}
        wm=(WindowManager)getSystemService(WINDOW_SERVICE);bubble=new TextView(this);bubble.setText("AI");bubble.setTextColor(Color.WHITE);bubble.setTextSize(16);bubble.setGravity(Gravity.CENTER);bubble.setBackgroundColor(0xFF6750D8);bubble.setOnClickListener(v->togglePanel());
        WindowManager.LayoutParams p=params(dp(58),dp(58));p.gravity=Gravity.TOP|Gravity.END;p.x=dp(14);p.y=dp(180);wm.addView(bubble,p);
    }
    private Button btn(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);return b;}
    private void togglePanel(){
        if(panel!=null){wm.removeView(panel);panel=null;return;}
        panel=new LinearLayout(this);panel.setOrientation(1);panel.setPadding(dp(12),dp(10),dp(12),dp(10));panel.setBackgroundColor(0xF01B1B29);
        TextView h=new TextView(this);h.setText("AI ASSISTENT · ZIEL-APP-MODUS");h.setTextColor(Color.WHITE);h.setTextSize(15);panel.addView(h);
        taskInput=new EditText(this);taskInput.setTextColor(Color.WHITE);taskInput.setHintTextColor(0xFFAAAAAA);taskInput.setHint("Auftrag für die ausgewählte App");panel.addView(taskInput,new LinearLayout.LayoutParams(-1,dp(58)));
        status=new TextView(this);status.setTextColor(0xFFE0DDF5);status.setTextSize(12);status.setText("Nur Bildschirmanalyse und Feedback. Es werden keine Aktionen ausgeführt.");panel.addView(status);
        Button start=btn("Agent starten");panel.addView(start);start.setOnClickListener(v->startAgent(taskInput.getText().toString()));
        Button stop=btn("STOP · sofort anhalten");stop.setTextColor(0xFFFF7777);panel.addView(stop);stop.setOnClickListener(v->requestStop());
        Button close=btn("Overlay schließen");panel.addView(close);close.setOnClickListener(v->stopSelf());
        WindowManager.LayoutParams p=params(dp(320),-2);p.gravity=Gravity.TOP|Gravity.END;p.x=dp(10);p.y=dp(248);p.flags=WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;wm.addView(panel,p);
    }
    private void startAgent(String task){
        if(running){updateStatus("Agent läuft bereits.");return;}
        if(task==null||task.trim().isEmpty()){updateStatus("Bitte einen Auftrag eingeben.");return;}
        ScreenActionService s=ScreenActionService.instance;
        if(s==null){updateStatus("Bedienungshilfe nicht aktiv.");return;}
        if(s.selectedTargetPackage().isEmpty()){updateStatus("Keine Ziel-App ausgewählt. Haupt-App öffnen.");return;}
        if(!s.isTargetAppActive()){updateStatus("Öffne zuerst die ausgewählte Ziel-App.");return;}
        if(ScreenCaptureService.latestFramePath()==null){updateStatus("Kein Bildschirmbild. Bildschirmfreigabe starten.");return;}
        running=true;stopRequested=false;worker.execute(()->runAgent(task.trim()));
    }
    private void runAgent(String task){
        int completed=0;
        try{
            updateStatus("Initialisiere lokale visuelle Erkennung …");agent=new LocalVisionAgent(getApplicationContext());
            long version=ScreenCaptureService.currentFrameVersion();
            for(int step=1;step<=12&&!stopRequested;step++){
                String frame=ScreenCaptureService.awaitNextFramePath(version,4000);
                if(frame==null){updateStatus("Kein neues Bildschirmbild. Agent pausiert.");break;}
                version=ScreenCaptureService.currentFrameVersion();
                ScreenActionService s=ScreenActionService.instance;
                if(s==null||!s.isTargetAppActive()){updateStatus("Ziel-App verlassen. Agent pausiert.");break;}
                updateStatus("Analyse · Schritt "+step+"/12 …");
                String raw=agent.analyze(task,frame,"",step,completed);
                if(stopRequested)break;
                if(!s.isTargetAppActive()){updateStatus("App-Wechsel erkannt. Keine Aktion ausgeführt.");break;}
                JSONObject d;try{d=new JSONObject(raw);}catch(Exception e){updateStatus("Ungültige Analyseantwort.");break;}
                String summary=d.optString("summary","Bild geprüft.");
                if(d.optBoolean("done",false)){updateStatus("Fertig: "+summary);break;}
                // Feedback-only mode: report the visual analysis and never perform screen actions.
                completed++;
                updateStatus("Bildschirm-Feedback "+completed+"/12: "+summary);
            }
            if(completed>=12&&!stopRequested)updateStatus("12 Bildanalysen abgeschlossen.");
            if(stopRequested)updateStatus("Agent gestoppt.");
        }catch(Exception e){updateStatus("Analysefehler: "+e.getClass().getSimpleName());}
        finally{if(agent!=null){try{agent.close();}catch(Exception ignored){}agent=null;}running=false;}
    }
    private void requestStop(){stopRequested=true;updateStatus("Stopp angefordert …");}
    private void updateStatus(String m){new Handler(Looper.getMainLooper()).post(()->{if(status!=null)status.setText(m);});}
    @Override public void onDestroy(){stopRequested=true;if(bubble!=null)wm.removeView(bubble);if(panel!=null)wm.removeView(panel);worker.shutdownNow();if(agent!=null){try{agent.close();}catch(Exception ignored){}}super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}