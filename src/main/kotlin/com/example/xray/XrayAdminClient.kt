package com.example.xray

import com.example.db.NodeEntity
import java.time.Instant
import java.util.UUID

data class XrayUser(
    val email: String,
    val uuid: UUID,
    val flow: String?,
    val expiresAt: Instant
)

interface XrayAdminClient {
    fun addUser(node: NodeEntity, user: XrayUser)
    fun removeUser(node: NodeEntity, email: String)
    fun ping(node: NodeEntity): Int
}
