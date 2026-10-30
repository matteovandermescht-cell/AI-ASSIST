package com.matteo.aiassistant;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.*;
import java.util.Locale;

public class ScreenActionService extends AccessibilityService {
    public static volatile ScreenActionService instance;
    @Override public void onServiceConnected() { super.onServiceConnected(); instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() { instance = null; }
    @Override public void onDestroy() { if (instance == this) instance = null; super.onDestroy(); }

    public String readVisibleText() {
        StringBuilder out = new StringBuilder();
        try {
            for (AccessibilityWindowInfo window : getWindows()) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                CharSequence pkg = root.getPackageName();
                if (pkg != null && getPackageName().contentEquals(pkg)) continue;
                walk(root, out, 0);
                if (out.length() > 0) break;
            }
        } catch (Exception ignored) {}
        if (out.length() == 0) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) walk(root, out, 0);
        }
        return out.length() == 0 ? "Keine lesbaren UI-Texte verfügbar." : out.toString();
    }

    private void walk(AccessibilityNodeInfo node, StringBuilder out, int depth) {
        if (node == null || depth > 18 || out.length() > 7000) return;
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        if (text != null && text.length() > 0) out.append(text).append(" | ");
        else if (description != null && description.length() > 0) out.append(description).append(" | ");
        for (int i = 0; i < node.getChildCount(); i++) walk(node.getChild(i), out, depth + 1);
    }

    public boolean safeTap(float x, float y) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo target = findClickableAt(root, x, y, 0);
        if (target == null) return false;
        String label = ((target.getText() == null ? "" : target.getText().toString()) + " " +
                (target.getContentDescription() == null ? "" : target.getContentDescription().toString())).toLowerCase(Locale.ROOT);
        if (label.trim().isEmpty() || isSensitiveLabel(label)) return false;
        Path path = new Path();
        path.moveTo(x, y);
        return dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 90)).build(), null, null);
    }

    private AccessibilityNodeInfo findClickableAt(AccessibilityNodeInfo node, float x, float y, int depth) {
        if (node == null || depth > 20) return null;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.contains((int)x, (int)y)) return null;
        for (int i = node.getChildCount() - 1; i >= 0; i--) {
            AccessibilityNodeInfo child = findClickableAt(node.getChild(i), x, y, depth + 1);
            if (child != null) return child;
        }
        return node.isClickable() && node.isEnabled() ? node : null;
    }

    private boolean isSensitiveLabel(String text) {
        String[] blocked = {"buy", "purchase", "checkout", "payment", "pay now", "kaufen", "bestellen",
                "delete", "löschen", "remove account", "password", "passwort", "login", "anmelden",
                "send", "senden", "publish", "veröffentlichen", "post", "share", "teilen", "transfer",
                "überweisen", "subscribe", "abonnieren", "confirm purchase"};
        for (String word : blocked) if (text.contains(word)) return true;
        return false;
    }

    public boolean safeSwipe(float x1, float y1, float x2, float y2, long durationMs) {
        DisplayMetrics metrics = new DisplayMetrics();
        ((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
        float marginX = metrics.widthPixels * 0.04f;
        float marginY = metrics.heightPixels * 0.04f;
        if (x1 < marginX || x2 < marginX || x1 > metrics.widthPixels - marginX || x2 > metrics.widthPixels - marginX ||
                y1 < marginY || y2 < marginY || y1 > metrics.heightPixels - marginY || y2 > metrics.heightPixels - marginY) return false;
        if (Math.abs(x2 - x1) > metrics.widthPixels * 0.85f || Math.abs(y2 - y1) > metrics.heightPixels * 0.85f) return false;
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        long duration = Math.max(180, Math.min(650, durationMs));
        return dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build(), null, null);
    }

    public boolean safeType(String value) {
        if (value == null || value.length() > 240) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (field == null || field.isPassword()) return false;
        CharSequence hint = field.getHintText();
        if (hint != null && isSensitiveLabel(hint.toString().toLowerCase(Locale.ROOT))) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }
}