# AI ASSISTENT 2.0 — Local Vision Agent

Privacy-first Android agent prototype. Model inference and screen analysis run on-device through LiteRT-LM. No Gemini endpoint, cloud AI API, or API key is used.

## Architecture
- LiteRT-LM Kotlin runtime for local model inference.
- User-selected .litertlm model stored in the app's private files directory.
- Android MediaProjection provides screenshots after explicit system consent.
- AccessibilityService exposes visible UI text and performs bounded interactions.
- The agent re-observes the screen after each action and plans the next step.
- Maximum 6 actions per task, minimum confidence 0.78, explicit stop control.

## Model requirement
Choose a multimodal vision-capable LiteRT-LM .litertlm model. A text-only model cannot interpret screenshots. The model is selected locally from device storage and copied into app-private storage. It is not bundled in the APK because vision models are large (often multiple GB). Download/import the model before going offline. Model compatibility and device memory depend on the selected model.

Official runtime guide: https://developers.google.com/edge/litert-lm/android

## Setup
1. Install the APK.
2. Import a compatible multimodal .litertlm model.
3. Enable AI ASSISTENT under Android Accessibility settings.
4. Grant overlay permission.
5. Start screen sharing and approve Android's MediaProjection dialog.
6. Open the floating AI bubble, describe a task, and start the agent.
7. Use STOP to halt the current run.

## Safety boundaries
- Maximum 6 actions per task.
- Confidence below 0.78 stops execution.
- Only tap, swipe, and short text-entry actions are supported.
- Tap actions require an accessibility node marked clickable.
- Password fields and labels associated with purchases, payments, deletion, account changes, publishing, sending, or sharing are blocked.
- Sensitive screen text causes the agent to stop.
- Android's screen-sharing notification remains visible; user can revoke projection in system UI.

These safeguards reduce risk but cannot guarantee that every app or screen is classified correctly. Keep the agent visible and supervised. This is a prototype, not a universal reliable autonomous operator.