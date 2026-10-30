package com.matteo.aiassistant

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File

/**
 * Local model controller. It never sends screen data over the network.
 * Calls are made from the agent's single background worker.
 */
class LocalVisionAgent(modelPath: String, cachePath: String) : AutoCloseable {
    private val engine: Engine
    private val conversation: Conversation

    init {
        val cache = File(cachePath).apply { mkdirs() }.absolutePath
        val gpu = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU(),
                visionBackend = Backend.GPU(),
                cacheDir = cache
            )
        )
        engine = try {
            gpu.initialize()
            gpu
        } catch (gpuError: Throwable) {
            try { gpu.close() } catch (_: Throwable) {}
            Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    visionBackend = Backend.CPU(),
                    cacheDir = cache
                )
            ).also { it.initialize() }
        }

        conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(
                    "You are a fast Android screen control policy. Inspect the screenshot and accessibility text. " +
                    "Return exactly one compact JSON object, no markdown and no explanation outside JSON. " +
                    "Schema: {done:boolean,summary:string,action:null|{type:'tap',x:number,y:number,confidence:number}|" +
                    "{type:'multiTap',points:[{x:number,y:number}],confidence:number}|" +
                    "{type:'swipe',x1:number,y1:number,x2:number,y2:number,duration:number,confidence:number}|" +
                    "{type:'type',text:string,confidence:number}}. " +
                    "Choose the next safe action only. Prefer direct obvious controls and short actions. " +
                    "Never guess coordinates. Return action null if uncertain. Never interact with purchases, payments, " +
                    "messages, sending, publishing, sharing, account/security settings, deletion, passwords, or login. " +
                    "Set done true when the user's goal is complete."
                ),
                samplerConfig = SamplerConfig(topK = 10, topP = 0.8, temperature = 0.1),
                maxOutputToken = 192
            )
        )
    }

    fun analyze(goal: String, imagePath: String, accessibilityText: String, step: Int, completed: Int): String {
        val prompt = "USER GOAL: " + goal +
            "\nSTEP: " + step + " of 12; completed actions: " + completed +
            "\nVISIBLE ACCESSIBILITY TEXT: " + accessibilityText.take(2500) +
            "\nInspect the current screenshot. The AI bubble may be visible; ignore it. " +
            "Return the single next safe action. Coordinates are screen pixels. " +
            "Use multiTap only when 2-4 ordinary clickable controls should genuinely be pressed at the same time. " +
            "Keep output short. No planning essay. JSON only."

        val response = conversation.sendMessage(
            Contents.of(Content.ImageFile(imagePath), Content.Text(prompt))
        )
        // Message.toString() is the API's printable response representation.
        return response.toString()
    }

    override fun close() {
        try { conversation.close() } finally { engine.close() }
    }
}
