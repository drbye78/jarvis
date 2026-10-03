package com.jarvis.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Static guard for the MCP LOCAL loopback cleartext exception.
 *
 * `McpUrlPolicy` permits `http`/`https` for `McpServerKind.LOCAL` only when the
 * host is loopback, and `McpDnsGuard.forServer(LOCAL)` requires every resolved
 * address to be loopback. That policy is enforced at the app layer, but the
 * platform blocks plain HTTP independently: `AndroidManifest.xml` sets
 * `android:usesCleartextTraffic="false"`, so a local `http://127.0.0.1:<port>`
 * server never connects unless the network security config carves out a scoped
 * exception. This test fails if that exception drifts out of signature with the
 * policy — a widened domain set would silently punch an SSRF hole around
 * `McpUrlPolicy`, which no other JVM test can see.
 *
 * Nothing here needs a device: the two files are read from the module source
 * tree using the same cwd-relative resolution as [RegisterReceiverGuardTest].
 */
class McpLoopbackSecurityConfigTest {

    private companion object {
        const val CONFIG_ATTR = "android:networkSecurityConfig=\"@xml/network_security_config\""
        const val CLEARTEXT_OFF = "false"
        const val CLEARTEXT_ON = "true"
        val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")
    }

    /** The app module's `src/main` tree, resolved from whatever the test cwd is. */
    private fun mainDir(): File {
        val relative = "src/main"
        val candidates = listOf(File(relative), File("app/$relative"))
        candidates.firstOrNull { it.isDirectory }?.let { return it }
        var walk: File? = File(".").absoluteFile
        repeat(4) {
            val current = walk
            if (current != null) {
                val candidate = File(current, "app/$relative")
                if (candidate.isDirectory) return candidate
                walk = current.parentFile
            }
        }
        assertTrue(
            "main source dir not found: $relative (wd=${File(".").absoluteFile})",
            candidates[0].isDirectory,
        )
        return candidates[0]
    }

    private fun parse(file: File): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun elements(root: Element, tag: String): List<Element> {
        val nodes = root.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun `manifest opts into the loopback network security config`() {
        val manifest = File(mainDir(), "AndroidManifest.xml").readText()
        assertTrue(
            "AndroidManifest.xml must reference @xml/network_security_config",
            manifest.contains(CONFIG_ATTR),
        )
        assertTrue(
            "usesCleartextTraffic=false must stay (no global cleartext)",
            manifest.contains("android:usesCleartextTraffic=\"false\""),
        )
    }

    @Test
    fun `network security config keeps the secure default and only loopback domains`() {
        val file = File(mainDir(), "res/xml/network_security_config.xml")
        assertTrue("missing ${file.path}", file.isFile)
        val root = parse(file)
        assertEquals("network-security-config", root.tagName)

        val baseConfigs = elements(root, "base-config")
        assertEquals("exactly one base-config", 1, baseConfigs.size)
        assertEquals(
            "base-config must keep cleartext off globally",
            CLEARTEXT_OFF,
            baseConfigs.single().getAttribute("cleartextTrafficPermitted"),
        )

        val domainConfigs = elements(root, "domain-config")
        assertEquals("exactly one domain-config", 1, domainConfigs.size)
        assertEquals(
            "the loopback domain-config enables cleartext",
            CLEARTEXT_ON,
            domainConfigs.single().getAttribute("cleartextTrafficPermitted"),
        )

        val domains = elements(root, "domain")
        assertEquals("exactly the loopback hosts", 3, domains.size)
        assertEquals(
            "domain hosts must be exactly the loopback set",
            LOOPBACK_HOSTS,
            domains.map { it.textContent.trim() }.toSet(),
        )
        domains.forEach { domain ->
            assertEquals(
                "includeSubdomains must stay false for ${domain.textContent}",
                "false",
                domain.getAttribute("includeSubdomains"),
            )
        }
    }
}
