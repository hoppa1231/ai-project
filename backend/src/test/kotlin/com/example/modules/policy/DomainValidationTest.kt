package com.example.modules.policy

import com.example.common.ApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DomainValidationTest {
    @Test fun rejectsRussianZonesIncludingIdnAndTrailingDot() {
        listOf("example.ru", "sub.example.su.", "пример.рф", "example.xn--p1ai", "EXAMPLE.RU", "ru", "su").forEach {
            assertFailsWith<ApiException>(it) { normalizeDomainValue(it) }
        }
    }
    @Test fun acceptsOtherDomainsAndNormalizesIdn() {
        assertEquals("example.com", normalizeDomainValue("EXAMPLE.COM."))
        assertEquals("example.ru.com", normalizeDomainValue("example.ru.com"))
        assertEquals("xn--e1afmkfd.com", normalizeDomainValue("пример.com"))
    }
    @Test fun rejectsUrlsAndWildcardMatchers() {
        listOf("https://example.com/path", "*.example.com", "-bad.example", "a..com").forEach {
            assertFailsWith<ApiException>(it) { normalizeDomainValue(it) }
        }
    }
}
