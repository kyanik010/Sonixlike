package com.example

import org.json.JSONObject

/**
 * Validates the account section returned by player_api.php.
 * Field-level validation is pure Kotlin so JVM tests do not depend on Android JSON stubs.
 */
object XtreamAccountValidator {
    fun validate(root: JSONObject, nowMillis: Long): JSONObject {
        val userInfo = root.optJSONObject("user_info")
            ?: throw IllegalArgumentException("استجابة الخادم لا تحتوي على معلومات الحساب")
        validateFields(
            userInfo.optInt("auth", 0),
            userInfo.optString("status", ""),
            userInfo.optString("exp_date", ""),
            nowMillis
        )
        return userInfo
    }

    fun validateFields(auth: Int, rawStatus: String, expiration: String, nowMillis: Long) {
        if (auth != 1) {
            throw IllegalArgumentException("بيانات الدخول غير صحيحة أو الاشتراك غير مصرح به")
        }
        val status = rawStatus.trim().lowercase()
        if (status.isBlank()) {
            throw IllegalArgumentException("استجابة الخادم لا تحتوي على حالة الاشتراك")
        }
        if (status in setOf("expired", "disabled", "banned", "inactive", "blocked", "revoked")) {
            throw IllegalArgumentException("الاشتراك منتهي أو موقوف: $rawStatus")
        }

        val expiry = expiration.trim()
        if (expiry.isNotEmpty() && !expiry.equals("null", ignoreCase = true) && expiry != "0") {
            val expirySeconds = expiry.toLongOrNull()
                ?: throw IllegalArgumentException("تاريخ انتهاء الاشتراك من الخادم غير صالح")
            if (expirySeconds < 0) {
                throw IllegalArgumentException("تاريخ انتهاء الاشتراك من الخادم غير صالح")
            }
            if (expirySeconds > 0 && expirySeconds <= nowMillis / 1000L) {
                throw IllegalArgumentException("انتهت صلاحية الاشتراك")
            }
        }
    }
}
