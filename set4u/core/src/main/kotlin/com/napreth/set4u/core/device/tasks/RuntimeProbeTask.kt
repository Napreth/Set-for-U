package com.napreth.set4u.core.device.tasks

import android.os.Build
import android.os.Process
import com.napreth.set4u.core.device.*
import kotlinx.coroutines.delay

@DeviceEntry("set4u.runtime.probe", spec = RuntimeProbeSpec::class)
class RuntimeProbeTask : DeviceTask<RuntimeProbeRequest, RuntimeProbeResult>() {
    override suspend fun execute(context: DeviceTaskContext, request: RuntimeProbeRequest): RuntimeProbeResult {
        delay(request.delayMillis)
        return RuntimeProbeResult(Build.VERSION.SDK_INT, Process.myUid(), context.target.toString(), request.text)
    }
}
