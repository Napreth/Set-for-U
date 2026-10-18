package com.napreth.set4u.core.settings

import com.napreth.set4u.core.expectFailure
import com.napreth.set4u.core.network.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MobileDataProtocolTest {
    private val descriptor = MobileDataSpec().descriptor
    @Test fun protocolPreservesUnknownStatesAndPinnedSubscriptionIdentity() {
        for (request in listOf(MobileDataRequest(), MobileDataRequest(true, 7), MobileDataRequest(false, 8)))
            assertEquals(request, descriptor.requestCodec.decode(descriptor.requestCodec.encode(request)))
        for (response in listOf(MobileDataState(MobileDataStatus.Ready, 7, false), MobileDataState(MobileDataStatus.NoActiveSubscription), MobileDataState(MobileDataStatus.DefaultSubscriptionChanged)))
            assertEquals(response, descriptor.responseCodec.decode(descriptor.responseCodec.encode(response)))
    }
    @Test fun malformedRequestsAndUnverifiableSuccessAreRejected() = runTest {
        expectFailure<IllegalStateException> { descriptor.requestCodec.decode(byteArrayOf(4, 0, 0, 0, 0)) }
        expectFailure<IllegalStateException> { descriptor.requestCodec.decode(descriptor.requestCodec.encode(MobileDataRequest()) + byteArrayOf(0)) }
        expectFailure<IllegalArgumentException> { descriptor.responseCodec.decode(byteArrayOf(0, -1, -1, -1, -1, -1)) }
        expectFailure<IllegalArgumentException> { MobileDataState(MobileDataStatus.NoActiveSubscription, 7, false) }
    }
}
