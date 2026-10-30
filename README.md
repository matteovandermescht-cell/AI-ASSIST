# AI ASSISTENT

Android overlay prototype with an AccessibilityService and Gemini-based UI planning.

## Included in version 1.0
- Floating overlay panel.
- Guided setup for Android accessibility and overlay permissions.
- Reads text exposed by the active Android accessibility tree.
- Sends the task and visible UI text to Gemini.
- Displays one proposed action; the user must explicitly confirm it.
- Supports tap, swipe, and setting text in the currently focused editable field.
- GitHub Actions debug APK build.

## Build
Open the Actions tab, select Build Android APK, and run the workflow manually. Download the AI-ASSISTENT-debug-apk artifact from the completed run.

## Android setup
1. Install the debug APK.
2. Open AI ASSISTENT and save your Gemini API key.
3. Enable AI ASSISTENT in Android Settings > Accessibility.
4. Grant the overlay permission.
5. Start the overlay.
6. Enter a task, analyze it, review the proposed action, and confirm it.

## Current limitations
- Version 1.0 reads accessibility text; it does not yet capture or send screenshots.
- It is not a continuous autonomous agent. Each proposed action requires separate confirmation.
- Apps that expose little accessibility information may not be understood correctly.
- API usage may incur charges. Keep your API key private.

The app does not request root access and does not run proposed actions without user confirmation.
