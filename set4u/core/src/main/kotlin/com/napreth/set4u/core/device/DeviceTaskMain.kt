package com.napreth.set4u.core.device

import java.io.*
import kotlinx.coroutines.runBlocking

/** Stripped from the host JAR; loaded only from the generated DEX by app_process. */
object DeviceTaskMain {
    @JvmStatic fun main(args: Array<String>) {
        val output = DataOutputStream(FileOutputStream(FileDescriptor.out))
        System.setOut(System.err)
        val request = DeviceWire.readRequest(DataInputStream(System.`in`))
        val registry = Class.forName("com.napreth.set4u.generated.GeneratedDeviceTasks")
            .getMethod("create").invoke(null) as DeviceTaskRegistry
        val result = runBlocking {
            registry.execute(request.taskId, request.version, request.context, request.payload)
        }
        DeviceWire.writeResponse(output, request.context.requestId, result)
    }
}
