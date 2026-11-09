package app.chspch.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SharedWebsiteParserTest {
    @Test fun extractsHostFromBrowserTextAndDiscardsPrivateUrlParts() {
        assertEquals(SharedWebsite("www.example.com", "DOMAIN_SUFFIX"),
            SharedWebsiteParser.parse("Заголовок страницы\nhttps://WWW.Example.COM:443/path?token=private#part"))
    }
    @Test fun preservesSubdomainInsteadOfGuessingPublicSuffix() {
        assertEquals("news.example.co.uk", SharedWebsiteParser.parse("https://news.example.co.uk/article").value)
    }
    @Test fun normalizesInternationalDomain() {
        assertEquals("xn--e1afmkfd.com", SharedWebsiteParser.parse("https://пример.com/статья").value)
    }
    @Test fun createsSingleAddressRules() {
        assertEquals(SharedWebsite("203.0.113.8/32", "IP_CIDR"), SharedWebsiteParser.parse("http://203.0.113.8:8080/path"))
        assertEquals(SharedWebsite("2001:db8::1/128", "IP_CIDR"), SharedWebsiteParser.parse("https://[2001:db8::1]/path"))
        assertEquals(SharedWebsite("2001:db8::1/128", "IP_CIDR"), SharedWebsiteParser.parse("https://[2001:db8::1]"))
    }
    @Test fun rejectsRussianZonesAndMalformedOrAmbiguousInput() {
        listOf("https://example.ru", "https://example.su./", "https://пример.рф", "https://example.xn--p1ai",
            "https://user:password@example.com", "https://example.com@evil.com", "https://999.1.2.3/", "https://127.1/",
            "https://-bad.example/", "https://example.com:99999", "https://example.com:", "https://example.com:wrong", "file:///tmp/private", "no link", "https://example.com https://other.com").forEach { input ->
            assertThrows(input, IllegalArgumentException::class.java) { SharedWebsiteParser.parse(input) }
        }
    }
    @Test fun repeatedLinksToSameSiteProduceOneDestination() {
        assertEquals("example.com", SharedWebsiteParser.parse("https://example.com/one https://example.com/two").value)
    }
}
