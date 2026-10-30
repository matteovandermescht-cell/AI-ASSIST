package com.matteo.aiassistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    private static final String PREFS="ai_assist_settings", TARGET="selected_target_package";
    private String selected="";
    private TextView chosen;
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density);}
    private Button button(String s){
        Button b=new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(-1);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF29283A));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(52)); p.topMargin=dp(8); b.setLayoutParams(p); return b;
    }
    @Override public void onCreate(Bundle b){
        super.onCreate(b); selected=getSharedPreferences(PREFS,0).getString(TARGET,"");
        getWindow().setStatusBarColor(0xFF10111A); getWindow().setNavigationBarColor(0xFF10111A);
        LinearLayout root=new LinearLayout(this); root.setOrientation(1); root.setPadding(dp(20),dp(24),dp(20),dp(20)); root.setBackgroundColor(0xFF10111A);
        TextView title=new TextView(this); title.setText("AI ASSISTENT 3.1"); title.setTextColor(-1); title.setTextSize(25); root.addView(title);
        TextView info=new TextView(this); info.setText("Lokale OCR + Objekterkennung. Bildschirmdaten werden lokal analysiert. Der Agent darf nur in der ausgewählten Ziel-App Aktionen ausführen und pausiert beim App-Wechsel."); info.setTextColor(0xFFBBBBCC); info.setTextSize(13); info.setPadding(0,dp(12),0,dp(12)); root.addView(info);
        Button pick=button("Ziel-App auswählen"); root.addView(pick);
        chosen=new TextView(this); chosen.setTextColor(0xFF8FE0A0); chosen.setPadding(0,dp(8),0,dp(8)); root.addView(chosen); refresh();
        pick.setOnClickListener(v->pickApp());
        Button consent=button("Einverständnis bestätigen"); root.addView(consent);
        consent.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Einverständnis")
            .setMessage("Ich erlaube AI-ASSIST, Bildschirmbilder der ausgewählten App aufzunehmen und lokal auszuwerten. Der Agent kann dort automatisch Aktionen ausführen. Beim Verlassen der Ziel-App pausiert er. Ich kann die Bildschirmfreigabe und Bedienungshilfe jederzeit in Android beenden.")
            .setNegativeButton("Ablehnen",null).setPositiveButton("Zustimmen",(d,w)->getSharedPreferences(PREFS,0).edit().putBoolean("consent",true).apply()).show());
        Button access=button("1 · Bedienungshilfe aktivieren"); root.addView(access); access.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        Button overlay=button("2 · Overlay-Berechtigung"); root.addView(overlay); overlay.setOnClickListener(v->{if(!Settings.canDrawOverlays(this))startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));});
        Button start=button("3 · Bildschirmfreigabe starten"); root.addView(start); start.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF6750D8)); start.setOnClickListener(v->beginCapture());
        Button stop=button("Agent stoppen"); root.addView(stop); stop.setOnClickListener(v->{stopService(new Intent(this,OverlayService.class));stopService(new Intent(this,ScreenCaptureService.class));});
        ScrollView scroll=new ScrollView(this); scroll.addView(root); setContentView(scroll);
    }
    private void refresh(){ if(chosen!=null) chosen.setText(selected.isEmpty()?"Keine Ziel-App ausgewählt.":"Ziel-App-Paket: "+selected); }
    private void pickApp(){
        Intent q=new Intent(Intent.ACTION_MAIN); q.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list=getPackageManager().queryIntentActivities(q,0);
        Map<String,ResolveInfo> unique=new TreeMap<>();
        for(ResolveInfo r:list){String p=r.activityInfo.packageName;if(!p.equals(getPackageName()))unique.put(p,r);}
        List<String> pkgs=new ArrayList<>(unique.keySet()); List<String> labels=new ArrayList<>();
        for(String p:pkgs) labels.add(unique.get(p).loadLabel(getPackageManager()).toString());
        if(labels.isEmpty())return;
        new AlertDialog.Builder(this).setTitle("Ziel-App auswählen").setItems(labels.toArray(new String[0]),(d,which)->{
            selected=pkgs.get(which);getSharedPreferences(PREFS,0).edit().putString(TARGET,selected).putBoolean("consent",false).apply();refresh();
        }).setNegativeButton("Abbrechen",null).show();
    }
    private void beginCapture(){
        if(selected.isEmpty()){Toast.makeText(this,"Zuerst Ziel-App auswählen.",Toast.LENGTH_LONG).show();return;}
        if(!getSharedPreferences(PREFS,0).getBoolean("consent",false)){Toast.makeText(this,"Bitte Einverständnis bestätigen.",Toast.LENGTH_LONG).show();return;}
        if(!Settings.canDrawOverlays(this)){Toast.makeText(this,"Overlay-Berechtigung fehlt.",Toast.LENGTH_LONG).show();return;}
        MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);startActivityForResult(m.createScreenCaptureIntent(),42);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==42&&result==RESULT_OK&&data!=null){
            Intent i=new Intent(this,ScreenCaptureService.class);i.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE,result);i.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA,data);
            if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
            startService(new Intent(this,OverlayService.class));Toast.makeText(this,"Freigabe aktiv. Öffne die ausgewählte App.",Toast.LENGTH_LONG).show();finish();
        }
    }
}