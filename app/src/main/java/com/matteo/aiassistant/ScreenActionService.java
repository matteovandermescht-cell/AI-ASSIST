package com.matteo.aiassistant;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Bundle;
import android.view.accessibility.*;
public class ScreenActionService extends AccessibilityService {
 public static volatile ScreenActionService instance;
 @Override public void onServiceConnected(){super.onServiceConnected();instance=this;}
 @Override public void onAccessibilityEvent(AccessibilityEvent e){}
 @Override public void onInterrupt(){instance=null;}
 @Override public void onDestroy(){if(instance==this)instance=null;super.onDestroy();}
 public String readVisibleText(){AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return "Keine sichtbaren UI-Elemente verfügbar.";StringBuilder b=new StringBuilder();walk(root,b,0);return b.length()==0?"Keine lesbaren Texte auf dem Bildschirm.":b.toString();}
 private void walk(AccessibilityNodeInfo n,StringBuilder b,int d){if(n==null||d>18||b.length()>7000)return;CharSequence t=n.getText(),c=n.getContentDescription();if(t!=null&&t.length()>0)b.append(t).append(" | ");else if(c!=null&&c.length()>0)b.append(c).append(" | ");for(int i=0;i<n.getChildCount();i++)walk(n.getChild(i),b,d+1);}
 public boolean tap(float x,float y){Path p=new Path();p.moveTo(x,y);return dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p,0,100)).build(),null,null);}
 public boolean swipe(float x1,float y1,float x2,float y2,long ms){Path p=new Path();p.moveTo(x1,y1);p.lineTo(x2,y2);return dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p,0,Math.max(150,ms))).build(),null,null);}
 public boolean setFocusedText(String value){AccessibilityNodeInfo r=getRootInActiveWindow();if(r==null)return false;AccessibilityNodeInfo f=r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);if(f==null)return false;Bundle a=new Bundle();a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,a);}
}
