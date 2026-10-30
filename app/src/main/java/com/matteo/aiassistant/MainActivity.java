package com.matteo.aiassistant;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.*;
public class MainActivity extends Activity {
 private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density);}
 private Button btn(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(-1);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF29283A));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(52));p.topMargin=dp(9);b.setLayoutParams(p);return b;}
 @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(0xFF10111A);getWindow().setNavigationBarColor(0xFF10111A);
 LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(22),dp(28),dp(22),dp(20));root.setBackgroundColor(0xFF10111A);
 TextView title=new TextView(this);title.setText("AI ASSISTENT");title.setTextColor(-1);title.setTextSize(27);root.addView(title);
 TextView sub=new TextView(this);sub.setText("Dein KI-Overlay für Android");sub.setTextColor(0xFFAAAABD);sub.setPadding(0,dp(5),0,dp(20));root.addView(sub);
 TextView label=new TextView(this);label.setText("Gemini API-Schlüssel");label.setTextColor(0xFFE2E0F0);root.addView(label);
 EditText key=new EditText(this);key.setSingleLine(true);key.setTextColor(-1);key.setHint("API-Schlüssel einfügen");key.setInputType( android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);key.setText(getSharedPreferences("ai",0).getString("gemini_key",""));root.addView(key,new LinearLayout.LayoutParams(-1,dp(54)));
 Button save=btn("API-Schlüssel speichern");root.addView(save);save.setOnClickListener(v->{getSharedPreferences("ai",0).edit().putString("gemini_key",key.getText().toString().trim()).apply();Toast.makeText(this,"Gespeichert",0).show();});
 TextView perms=new TextView(this);perms.setText("Berechtigungen");perms.setTextColor(0xFFE2E0F0);perms.setPadding(0,dp(20),0,0);root.addView(perms);
 Button a11y=btn("1 · Bedienungshilfe aktivieren");root.addView(a11y);a11y.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
 Button overlay=btn("2 · Overlay-Berechtigung");root.addView(overlay);overlay.setOnClickListener(v->{if(!Settings.canDrawOverlays(this))startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));else Toast.makeText(this,"Overlay ist bereits erlaubt",0).show();});
 Button start=btn("KI-Overlay starten");start.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF6750D8));root.addView(start);start.setOnClickListener(v->{if(!Settings.canDrawOverlays(this)){Toast.makeText(this,"Bitte zuerst Overlay erlauben",1).show();return;}startService(new Intent(this,OverlayService.class));finish();});
 TextView note=new TextView(this);note.setText("Die Bedienungshilfe muss manuell in Android aktiviert werden. KI-Aktionen werden vor der Ausführung angezeigt und müssen bestätigt werden.");note.setTextColor(0xFF9999AA);note.setTextSize(12);note.setPadding(0,dp(18),0,0);root.addView(note);setContentView(root);
 }
}
