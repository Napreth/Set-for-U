package com.napreth.set4u.core.bridge

enum class BridgeTransport { Adb, Shizuku, Local, Unknown }
enum class ExecutionIdentity { App, Shell, Root, System, Unknown }
data class ExecutionEnvironment(val transport: BridgeTransport, val uid: Int?, val permissions: Set<String> = emptySet()) {
    val identity: ExecutionIdentity get() = when (uid) { 0 -> ExecutionIdentity.Root; 1000 -> ExecutionIdentity.System; 2000 -> ExecutionIdentity.Shell; null -> ExecutionIdentity.Unknown; else -> ExecutionIdentity.App }
}
fun interface ExecutionEnvironmentSource { suspend fun read(): ExecutionEnvironment }
