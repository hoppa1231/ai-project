package com.example.xray

import com.example.db.NodeEntity
import java.time.Instant
import java.util.UUID

data class XrayUser(
    val email: String,
    val uuid: UUID,
    val flow: String?,
    val expiresAt: Instant,
    val trafficLimitBytes: Long = 0,
    val telegramId: Long? = null
)

data class XrayNodeClient(
    val inboundId: Int,
    val inboundRemark: String,
    val inboundTag: String,
    val email: String,
    val uuid: String?,
    val flow: String?,
    val enabled: Boolean,
    val totalBytes: Long,
    val upBytes: Long,
    val downBytes: Long,
    val expiryTime: Long,
    val limitIp: Int,
    val subId: String?,
    val telegramId: Long?
)

interface XrayAdminClient {
    fun addUser(node: NodeEntity, user: XrayUser)
    fun removeUser(node: NodeEntity, email: String)
    fun ping(node: NodeEntity): Int
    fun listClients(node: NodeEntity): List<XrayNodeClient>
    fun updateClientTrafficLimit(node: NodeEntity, email: String, totalBytes: Long)
}
