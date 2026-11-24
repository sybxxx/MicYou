package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.viewmodel.ConnectionMode
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionErrorTest {
    @Test
    fun elapsedDialUsesTimeoutInsteadOfUnknownError() {
        for (error in listOf(
            ConnectException("Connection timed out"),
            Exception("Localized wrapper", SocketTimeoutException("TCP connection timed out"))
        )) {
            assertEquals(
                ConnectionErrorType.NetworkTimeout,
                ConnectionErrorHelper.analyzeError(error, ConnectionMode.Wifi)
            )
        }
    }

    @Test
    fun refusingTheConnectionIsNotATimeout() {
        assertEquals(
            ConnectionErrorType.ConnectionRefused,
            ConnectionErrorHelper.analyzeError(ConnectException("Connection refused"), ConnectionMode.Wifi)
        )
    }
}
