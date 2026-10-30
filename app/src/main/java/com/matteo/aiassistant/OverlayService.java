package com.matteo.aiassistant;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.*;
import android.graphics.Color;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class OverlayService extends Service {
 private WindowManager wm; private TextView bubble,result; private LinearLayout panel;
 private final ExecutorService worker=Executors.newSingleThreadExecutor(); private JSONObject pending;
 private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density);}
 private WindowManager.LayoutParams lp(int w,int h){return new WindowManager.LayoutParams(w,h,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);}
 @Override public void onCreate(){super.onCreate();wm=(WindowManager)getSystemService(WINDOW_SERVICE);
 bubble=new TextView(this);bubble.setText("AI");bubble.setTextColor(-1);bubble.setTextSize(16);bubble.setGravity(Gravity.CENTER);bubble.setBackgroundColor(0xFF6750D8);bubble.setOnClickListener(v->toggle());
 WindowManager.LayoutParams p=lp(dp(58),dp(58));p.gravity=Gravity.TOP|Gravity.END;p.x=dp(14);p.y=dp(180);wm.addView(bubble,p);}
 private Button button(String text){Button b=new Button(this);b.setText(text);b.setAllCaps(false);return b;}
 private void toggle(){if(panel!=null){wm.removeView(panel);panel=null;return;}
 panel=new LinearLayout(this);panel.setOrientation(1);panel.setPadding(dp(12),dp(10),dp(12),dp(10));panel.setBackgroundColor(0xF01B1B29);
 TextView head=new TextView(this);head.setText("AI ASSISTENT · Overlay");head.setTextColor(-1);head.setTextSize(16);panel.addView(head);
 EditText task=new EditText(this);task.setTextColor(-1);task.setHintTextColor(0xFFAAAAAA);task.setHint("Was soll ich auf dem Bildschirm tun?");panel.addView(task,new LinearLayout.LayoutParams(-1,dp(58)));
 result=new TextView(this);result.setTextColor(0xFFE0DDF5);result.setTextSize(12);result.setText("Bereit. Die KI liest sichtbare UI-Texte.");panel.addView(result);
 Button ask=button("KI analysieren");panel.addView(ask);ask.setOnClickListener(v->ask(task.getText().toString()));
 Button run=button("Vorgeschlagene Aktion ausführen");panel.addView(run);run.setOnClickListener(v->execute());
 Button close=button("Overlay schließen");panel.addView(close);close.setOnClickListener(v->stopSelf());
 WindowManager.LayoutParams p=lp(dp(315),-2);p.gravity=Gravity.TOP|Gravity.END;p.x=dp(10);p.y=dp(248);p.flags=WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;wm.addView(panel,p);}
 private void ask(String task){if(task.trim().isEmpty()){result.setText("Bitte Aufgabe eingeben.");return;}
 ScreenActionService s=ScreenActionService.instance;if(s==null){result.setText("Bitte zuerst die Bedienungshilfe aktivieren.");return;}
 String key=getSharedPreferences("ai",MODE_PRIVATE).getString("gemini_key","");if(key.isEmpty()){result.setText("API-Schlüssel fehlt. Öffne die App und speichere deinen Gemini-Schlüssel.");return;}
 String screen=s.readVisibleText();result.setText("KI analysiert sichtbare UI-Texte …");
 worker.execute(()->{try{
 JSONObject part=new JSONObject().put("text","Du bist ein Android UI-Assistent. Nutzeraufgabe: "+task+"\nSichtbare UI-Texte: "+screen+"\nAntworte ausschließlich als JSON mit explanation und action. action ist null oder ein Objekt: {type:tap,x:number,y:number}, {type:swipe,x1:number,y1:number,x2:number,y2:number,duration:number}, {type:type,text:string}. Erfinde keine Koordinaten. Wenn die Informationen nicht reichen, setze action auf null.");
 JSONObject body=new JSONObject().put("contents",new JSONArray().put(new JSONObject().put("parts",new JSONArray().put(part)))).put("generationConfig",new JSONObject().put("responseMimeType","application/json"));
 URL url=new URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key="+URLEncoder.encode(key,"UTF-8"));
 HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setRequestMethod("POST");c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
 try(OutputStream os=c.getOutputStream()){os.write(body.toString().getBytes(StandardCharsets.UTF_8));}
 InputStream in=c.getResponseCode()<400?c.getInputStream():c.getErrorStream();ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
 JSONObject response=new JSONObject(out.toString("UTF-8"));String raw=response.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
 JSONObject parsed=new JSONObject(raw);pending=parsed.optJSONObject("action");String explanation=parsed.optString("explanation","Keine Erklärung erhalten.");
 post(()->result.setText(explanation+(pending==null?"\nKeine ausführbare Aktion vorgeschlagen.":"\nAktion bereit – bitte prüfen und bestätigen.")));c.disconnect();
 }catch(Exception e){post(()->result.setText("KI-Fehler: "+e.getMessage()));}});}
 private void execute(){if(pending==null){result.setText("Keine bestätigbare Aktion vorhanden.");return;}ScreenActionService s=ScreenActionService.instance;if(s==null){result.setText("Bedienungshilfe ist nicht aktiv.");return;}
 try{String t=pending.getString("type");boolean ok=false;
 if("tap".equals(t))ok=s.tap((float)pending.getDouble("x"),(float)pending.getDouble("y"));
 else if("swipe".equals(t))ok=s.swipe((float)pending.getDouble("x1"),(float)pending.getDouble("y1"),(float)pending.getDouble("x2"),(float)pending.getDouble("y2"),pending.optLong("duration",350));
 else if("type".equals(t))ok=s.setFocusedText(pending.getString("text"));
 result.setText(ok?"Aktion an Android übergeben.":"Aktion konnte nicht ausgeführt werden.");pending=null;
 }catch(Exception e){result.setText("Ungültige Aktion: "+e.getMessage());}}
 private void post(Runnable r){new android.os.Handler(getMainLooper()).post(r);}
 @Override public void onDestroy(){if(bubble!=null)wm.removeView(bubble);if(panel!=null)wm.removeView(panel);worker.shutdownNow();super.onDestroy();}
 @Override public IBinder onBind(Intent i){return null;}
}
