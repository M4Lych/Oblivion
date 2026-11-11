package com.m4lych.oblivion

import com.m4lych.oblivion.logging.AppLogger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLoggerRedactionTest {
    @Test fun redactCommonCredentialFieldsAndJwtLikeTokens() {
        val cleaned = AppLogger.redact("Authorization: Bearer abc123 access_token=secret password: hunter2 eyJabcdefghijklmno.payload.signature")
        assertTrue(cleaned.contains("[REDACTED]"))
        assertTrue(cleaned.contains("[REDACTED_TOKEN]"))
        assertFalse(cleaned.contains("abc123"))
        assertFalse(cleaned.contains("secret"))
        assertFalse(cleaned.contains("hunter2"))
        assertFalse(cleaned.contains("eyJabcdefghijklmno"))
    }
}
