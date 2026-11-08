package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class XtreamAccountValidatorTest {
    private val nowMillis = 1_800_000_000_000L

    @Test
    fun acceptsActiveAuthorizedAccountWithFutureExpiry() {
        XtreamAccountValidator.validateFields(
            auth = 1,
            rawStatus = " Active ",
            expiration = (nowMillis / 1000L + 86_400L).toString(),
            nowMillis = nowMillis
        )
    }

    @Test
    fun acceptsZeroAndNullExpiryAsProviderDefinedUnlimited() {
        XtreamAccountValidator.validateFields(1, "Active", "0", nowMillis)
        XtreamAccountValidator.validateFields(1, "Active", "null", nowMillis)
        XtreamAccountValidator.validateFields(1, "Active", "", nowMillis)
    }

    @Test
    fun rejectsUnauthorizedCredentials() {
        assertThrows(IllegalArgumentException::class.java) {
            XtreamAccountValidator.validateFields(0, "Active", "0", nowMillis)
        }
    }

    @Test
    fun rejectsMissingStatus() {
        assertThrows(IllegalArgumentException::class.java) {
            XtreamAccountValidator.validateFields(1, " ", "0", nowMillis)
        }
    }

    @Test
    fun rejectsBlockedAndExpiredStatusesCaseInsensitively() {
        listOf("expired", "disabled", "banned", "inactive", "blocked", "revoked").forEach { status ->
            assertThrows(IllegalArgumentException::class.java) {
                XtreamAccountValidator.validateFields(1, status.uppercase(), "0", nowMillis)
            }
        }
    }

    @Test
    fun rejectsMalformedOrNegativeExpiry() {
        listOf("tomorrow", "-1").forEach { expiry ->
            assertThrows(IllegalArgumentException::class.java) {
                XtreamAccountValidator.validateFields(1, "Active", expiry, nowMillis)
            }
        }
    }

    @Test
    fun rejectsExpiryAtOrBeforeCurrentTime() {
        assertThrows(IllegalArgumentException::class.java) {
            XtreamAccountValidator.validateFields(
                1, "Active", (nowMillis / 1000L).toString(), nowMillis
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            XtreamAccountValidator.validateFields(
                1, "Active", (nowMillis / 1000L - 1L).toString(), nowMillis
            )
        }
    }
}