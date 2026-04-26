package com.example.xray

import com.example.db.NodeEntity

class GrpcXrayAdminClient : XrayAdminClient {
    override fun addUser(node: NodeEntity, user: XrayUser) {
        throw UnsupportedOperationException("XRAY_MODE=grpc selected, but gRPC client is not wired yet")
    }

    override fun removeUser(node: NodeEntity, email: String) {
        throw UnsupportedOperationException("XRAY_MODE=grpc selected, but gRPC client is not wired yet")
    }

    override fun ping(node: NodeEntity): Int {
        throw UnsupportedOperationException("XRAY_MODE=grpc selected, but gRPC client is not wired yet")
    }
}
