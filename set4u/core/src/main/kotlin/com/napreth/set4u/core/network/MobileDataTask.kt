package com.napreth.set4u.core.network

import android.content.Context
import android.os.Build
import android.os.Looper
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.napreth.set4u.core.device.*
import com.napreth.set4u.core.session.DeviceTarget

/** Runs in app_process with the bridge identity, never in the desktop JVM or application process. */
@DeviceEntry("network.mobile_data", spec = MobileDataSpec::class)
class MobileDataTask : DeviceTask<MobileDataRequest, MobileDataState>() {
    @Suppress("DEPRECATION")
    override suspend fun execute(context: DeviceTaskContext, request: MobileDataRequest): MobileDataState {
        require(context.target == DeviceTarget())
        check(Build.VERSION.SDK_INT >= 31) { "移动数据操作需要 Android 12 或更高版本" }
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("systemMain").invoke(null)
        val systemContext = activityThread.getMethod("getSystemContext").invoke(thread) as Context
        val shellContext = systemContext.createPackageContext("com.android.shell", 0)
        val subscriptions = checkNotNull(shellContext.getSystemService(SubscriptionManager::class.java))
        val subscriptionId = SubscriptionManager.getDefaultDataSubscriptionId()
        if (request.expectedSubscriptionId != null && request.expectedSubscriptionId != subscriptionId)
            return MobileDataState(MobileDataStatus.DefaultSubscriptionChanged)
        if (!SubscriptionManager.isValidSubscriptionId(subscriptionId) || !subscriptions.isActiveSubscriptionId(subscriptionId))
            return MobileDataState(MobileDataStatus.NoActiveSubscription)
        val telephony = checkNotNull(shellContext.getSystemService(TelephonyManager::class.java)).createForSubscriptionId(subscriptionId)
        request.desired?.let { telephony.setDataEnabledForReason(TelephonyManager.DATA_ENABLED_REASON_USER, it) }
        val enabled = telephony.isDataEnabledForReason(TelephonyManager.DATA_ENABLED_REASON_USER)
        if (SubscriptionManager.getDefaultDataSubscriptionId() != subscriptionId)
            return MobileDataState(MobileDataStatus.DefaultSubscriptionChanged)
        return MobileDataState(MobileDataStatus.Ready, subscriptionId, enabled)
    }
}
