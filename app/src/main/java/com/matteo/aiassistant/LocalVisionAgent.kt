package com.matteo.aiassistant

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig

class LocalVisionAgent(modelPath: String) : AutoCloseable {
    private val engine: Engine
    private val conversation: Conversation

    init {
        engine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU()
            )
        )
        engine.initialize()
        conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(
                    "You are a careful Android screen task planner. You receive a screen image and accessibility text. " +
                    "Return only JSON with keys done, summary, action. Never invent UI elements or coordinates. " +
                    "Choose at most one action. Allowed actions: tap with x,y; swipe with x1,y1,x2,y2,duration; " +
                    "type with text. Every action must include confidence from 0 to 1. If uncertain or sensitive, " +
                    "return action null. Never initiate purchases, payments, account changes, deletion, messages, " +
                    "publishing, sharing, or security changes. If goal is complete, set done true."
                ),
                samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.2),
                maxOutputToken = 512
            )
        )
    }

    fun analyze(goal: String, imagePath: String, accessibilityText: String, step: Int, completed: Int): String {
        val prompt = "Goal from user: " + goal +
            "\nCurrent step: " + step + " of 6. Successfully completed actions: " + completed +
            "\nAccessibility text visible on the current screen:\n" + accessibilityText.take(5000) +
            "\nInspect the attached screenshot. Ignore the AI ASSISTENT floating bubble. " +
            "Decide the single safest next action that directly advances the user's goal. " +
            "Coordinates must be inside the visible screen. For taps, choose a visible ordinary interface control, " +
            "never a purchase, delete, send, publish, share, login, or account control. " +
            "If the action cannot be identified with high confidence, return action null. " +
            "Return JSON only, for example: " +
            """{"done":false,"summary":"Opening the visible menu","action":{"type":"tap","x":120,"y":240,"confidence":0.91}}""" +
            " Or: " +
            """{"done":true,"summary":"The requested task is complete","action":null}"""

        val message = conversation.sendMessage(
            Contents.of(Content.ImageFile(imagePath), Content.Text(prompt))
        )
        return message.text
    }

    override fun close() {
        try { conversation.close() } finally { engine.close() }
    }
}