package com.securevpn.agent

import io.ktor.server.application.Application

data class AgentConfig(
    val agentToken: String,
    val xui: ThreeXuiConfig,
    private val inboundMap: Map<String, Int>
) {
    fun inboundIdFor(inboundTag: String?): Int {
        val normalizedTag = inboundTag?.trim().orEmpty()
        if (normalizedTag.isNotBlank()) {
            inboundMap[normalizedTag]?.let { return it }
        }
        return xui.defaultInboundId
    }

    companion object {
        fun load(application: Application): AgentConfig {
            val cfg = application.environment.config
            val agentToken = cfg.propertyOrNull("agent.token")?.getString()?.takeIf { it.isNotBlank() }
                ?: "change-agent-token"
            val defaultInboundId = cfg.propertyOrNull("agent.xui.inboundId")?.getString()?.toIntOrNull()
                ?: 1
            val inboundMap = parseInboundMap(cfg.propertyOrNull("agent.xui.inboundMap")?.getString())
            return AgentConfig(
                agentToken = agentToken,
                xui = ThreeXuiConfig(
                    baseUrl = cfg.propertyOrNull("agent.xui.baseUrl")?.getString()?.takeIf { it.isNotBlank() }
                        ?: "http://127.0.0.1:2053",
                    username = cfg.propertyOrNull("agent.xui.username")?.getString()?.takeIf { it.isNotBlank() }
                        ?: "admin",
                    password = cfg.propertyOrNull("agent.xui.password")?.getString()?.takeIf { it.isNotBlank() }
                        ?: "admin",
                    defaultInboundId = defaultInboundId,
                    twoFactorCode = cfg.propertyOrNull("agent.xui.twoFactorCode")?.getString()?.takeIf { it.isNotBlank() }
                ),
                inboundMap = inboundMap
            )
        }

        private fun parseInboundMap(raw: String?): Map<String, Int> {
            if (raw.isNullOrBlank()) return emptyMap()
            return raw.split(',')
                .mapNotNull { item ->
                    val parts = item.split(':', limit = 2)
                    val tag = parts.getOrNull(0)?.trim().orEmpty()
                    val id = parts.getOrNull(1)?.trim()?.toIntOrNull()
                    if (tag.isBlank() || id == null) null else tag to id
                }
                .toMap()
        }
    }
}

data class ThreeXuiConfig(
    val baseUrl: String,
    val username: String,
    val password: String,
    val defaultInboundId: Int,
    val twoFactorCode: String?
)
