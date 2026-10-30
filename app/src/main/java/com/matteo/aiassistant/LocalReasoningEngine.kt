package com.matteo.aiassistant

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.Closeable

/** Local language-model reasoning. The .litertlm weights stay outside the APK. */
class LocalReasoningEngine(modelPath: String, cacheDir: String) : Closeable {
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    init {
        val created = Engine(
            EngineConfig(modelPath = modelPath, backend = Backend.CPU(), cacheDir = cacheDir)
        )
        try {
            created.initialize()
            engine = created
            conversation = created.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(
                        """
                        You are a cautious on-device screen assistant. Interpret only the supplied UI evidence.
                        Return exactly one JSON object:
                        {"action":"wait|tap|swipe|done","x":0,"y":0,"x2":0,"y2":0,"reason":"short reason"}
                        Use wait if uncertain or if evidence does not clearly support an ordinary navigation action.
                        Never choose purchases, payments, account changes, deletion, login, passwords, private messages,
                        posting, sharing, sending, installation, permissions, or security controls.
                        Never invent coordinates. Prefer wait over guessing.
                        """.trimIndent()
                    )
                )
            )
        } catch (error: Exception) {
            try { created.close() } catch (_: Exception) {}
            throw error
        }
    }

    fun decide(screenEvidence: String): String {
        val active = conversation ?: error("Reasoning engine is not initialized")
        return active.sendMessage(
            "Visible screen evidence:\n" + screenEvidence.take(6000) +
                "\nChoose one safe next action using the required JSON."
        )
    }

    override fun close() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
    }
}
