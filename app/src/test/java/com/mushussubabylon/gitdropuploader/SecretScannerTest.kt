package com.mushussubabylon.gitdropuploader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sample secrets are assembled from pieces at run time, so this file never contains a whole
 * token (GitHub's secret scanning and GitDrop's own review would flag it otherwise).
 */
class SecretScannerTest {

    private val scanner = SecretScanner()

    private fun join(vararg parts: String) = parts.joinToString("")

    private fun scan(path: String, text: String) = scanner.scanContent(path, text.toByteArray(Charsets.UTF_8))

    private fun List<SecretFinding>.has(kind: SecretKind, provider: String? = null) =
        any { it.kind == kind && (provider == null || it.provider == provider) }

    // ------------------------------------------------------------ file names

    @Test
    fun envFilesAreFlaggedButTemplatesAreNot() {
        assertTrue(scanner.scanName(".env").has(SecretKind.ENV_FILE))
        assertTrue(scanner.scanName("config/.env.production").has(SecretKind.ENV_FILE))
        assertTrue(scanner.scanName("prod.env").has(SecretKind.ENV_FILE))
        assertTrue(scanner.scanName(".env.example").isEmpty())
        assertTrue(scanner.scanName("keystore.properties.example").isEmpty())
    }

    @Test
    fun keyAndKeystoreFilesAreFlagged() {
        assertTrue(scanner.scanName("app/release.jks").has(SecretKind.KEY_FILE))
        assertTrue(scanner.scanName("my.KEYSTORE").has(SecretKind.KEY_FILE))
        assertTrue(scanner.scanName("home/.ssh/id_ed25519").has(SecretKind.KEY_FILE))
        assertTrue(scanner.scanName("home/.ssh/id_ed25519.pub").isEmpty())
        assertTrue(scanner.scanName("keystore.properties").has(SecretKind.CREDENTIALS_FILE))
    }

    @Test
    fun ordinarySourceFilesAreNotFlagged() {
        assertTrue(scanner.scanName("src/Main.kt").isEmpty())
        assertTrue(scanner.scanName("README.md").isEmpty())
        assertTrue(scanner.scanName("app/build.gradle.kts").isEmpty())
    }

    @Test
    fun binaryExtensionsAreNotScannedAsText() {
        assertFalse(scanner.looksTextual("photo.JPG"))
        assertFalse(scanner.looksTextual("a/b/app.apk"))
        assertTrue(scanner.looksTextual("a/b/notes.txt"))
        assertTrue(scanner.looksTextual("Makefile"))
    }

    // ------------------------------------------------------------ tokens

    @Test
    fun githubTokenIsFoundWithProviderAndLine() {
        val token = join("gh", "p_", "aB3dE5gH7jK9mN1pQ3sT5vX7zA9cD1fG3hJ5")
        val found = scan("notes.txt", "first line\nsecond line\nkey = $token\n")
        assertTrue(found.has(SecretKind.TOKEN, "GitHub"))
        assertEquals(3, found.first { it.kind == SecretKind.TOKEN }.line)
    }

    @Test
    fun awsDocumentationExampleKeyIsIgnored() {
        assertTrue(scan("a.py", join("key = \"AKIA", "IOSFODNN7EXAMPLE\"")).isEmpty())
        assertTrue(scan("a.py", join("key = \"AKIA", "Q4ZDEFGHIJKLMNOP\"")).has(SecretKind.TOKEN, "AWS"))
    }

    @Test
    fun publishableKeysAreIgnored() {
        assertTrue(scan("a.rb", join("key = \"pk_", "live_4eC39HqLyjWDarjtT1zdp7dc\"")).isEmpty())
        assertTrue(scan("a.rb", join("key = \"sk_", "live_4eC39HqLyjWDarjtT1zdp7dc\"")).has(SecretKind.TOKEN, "Stripe"))
    }

    @Test
    fun privateKeyBlocksAreFound() {
        for (label in listOf("RSA ", "EC ", "OPENSSH ", "")) {
            val text = join("-----BEGIN ", label, "PRIVATE KEY-----\nMIIE\n")
            assertTrue(label, scan("id", text).has(SecretKind.PRIVATE_KEY))
        }
        assertTrue(scan("a.pem", join("-----BEGIN ", "PUBLIC KEY-----\nMIIB")).isEmpty())
    }

    // ------------------------------------------------------------ passwords

    @Test
    fun passwordAssignmentsAreFound() {
        assertTrue(scan("A.kt", join("val storePassword = \"", "gdr0p!Keystore2026\"")).has(SecretKind.PASSWORD))
        assertTrue(scan("a.json", join("{\"api_key\": \"", "9f8a7b6c5d4e3f2a1b0c\"}")).has(SecretKind.PASSWORD))
        assertTrue(scan("keystore.properties", join("storePassword=", "Zq8vK2mXp9Lw\n")).has(SecretKind.PASSWORD))
        assertTrue(scan(".env", join("DB_PASSWORD=", "Zq8vK2mXp9Lw # production")).has(SecretKind.PASSWORD))
    }

    @Test
    fun passwordInsideAUrlIsFoundButPlaceholdersAreNot() {
        assertTrue(scan("a.yml", join("url: postgres://app:", "Zq8vK2mXp9@db.internal/app")).has(SecretKind.PASSWORD))
        assertTrue(scan("a.md", "use https://user:password@host.example/x").isEmpty())
        assertTrue(scan("a.yml", "postgres://postgres:postgres@localhost/db").isEmpty())
        assertTrue(scan("a.md", "see https://example.com:8080/path").isEmpty())
    }

    @Test
    fun placeholdersAndCodeThatMerelyReadsPasswordsAreNotFlagged() {
        assertTrue(scan("keystore.properties", "storePassword=YOUR_STORE_PASSWORD").isEmpty())
        assertTrue(scan("a.kt", "val password = \"\"").isEmpty())
        assertTrue(scan("a.yml", "password: changeme123").isEmpty())
        assertTrue(scan("a.yml", "password: \${DB_PASSWORD}").isEmpty())
        assertTrue(scan("a.kt", "val password = passwordInput.text.toString()").isEmpty())
        assertTrue(scan("a.xml", "<EditText android:hint=\"@string/password_hint\" />").isEmpty())
        assertTrue(scan("README.md", join("DB_PASSWORD=", "Zq8vK2mXp9Lw")).isEmpty()) // settings syntax only counts in settings files
    }

    // ------------------------------------------------------------ robustness

    @Test
    fun binaryDataIsNotScanned() {
        val token = join("gh", "p_", "aB3dE5gH7jK9mN1pQ3sT5vX7zA9cD1fG3hJ5")
        val data = (token + "\u0000\u0000").toByteArray(Charsets.ISO_8859_1)
        assertTrue(scanner.scanContent("a.bin", data).isEmpty())
        assertTrue(scanner.scanContent("empty.txt", ByteArray(0)).isEmpty())
    }

    @Test
    fun aFileWithManySecretsReportsAtMostAFewFindings() {
        val token = join("gh", "p_", "aB3dE5gH7jK9mN1pQ3sT5vX7zA9cD1fG3hJ5")
        val text = (1..200).joinToString("\n") { "t$it = $token" }
        assertTrue(scan("big.txt", text).size <= 6)
    }
}
