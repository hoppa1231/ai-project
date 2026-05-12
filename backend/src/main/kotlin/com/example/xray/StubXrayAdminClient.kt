package com.example.xray

import com.example.db.NodeEntity
import org.slf4j.LoggerFactory

class StubXrayAdminClient : XrayAdminClient {
    private val logger = LoggerFactory.getLogger(StubXrayAdminClient::class.java)

    override fun addUser(node: NodeEntity, user: XrayUser) {
        logger.info("[stub-xray] addUser node={} email={} uuid={}", node.name, user.email, user.uuid)
    }

    override fun removeUser(node: NodeEntity, email: String) {
        logger.info("[stub-xray] removeUser node={} email={}", node.name, email)
    }

    override fun ping(node: NodeEntity): Int {
        logger.debug("[stub-xray] ping node={}", node.name)
        return 5
    }

    override fun listClients(node: NodeEntity): List<XrayNodeClient> {
        logger.info("[stub-xray] listClients node={}", node.name)
        return emptyList()
    }

    override fun updateClientTrafficLimit(node: NodeEntity, email: String, totalBytes: Long) {
        logger.info("[stub-xray] updateClientTrafficLimit node={} email={} totalBytes={}", node.name, email, totalBytes)
    }
}
