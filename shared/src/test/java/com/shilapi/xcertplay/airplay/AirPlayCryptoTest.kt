package com.shilapi.xcertplay.airplay

import org.conscrypt.Conscrypt
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.SecureRandom
import java.security.Security

class AirPlayCryptoTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun registerConscrypt() {
            if (Security.getProvider("Conscrypt") == null) {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
            }
            AirPlayCrypto.redetectAeadEngine()
        }

        private fun hex(value: String): ByteArray =
            value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    @After
    fun restoreEngineDetection() = AirPlayCrypto.redetectAeadEngine()

    // RFC 8439 §2.8.2 AEAD_CHACHA20_POLY1305 test vector.
    private val rfcKey = hex(
        "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f",
    )
    private val rfcNonce = hex("070000004041424344454647")
    private val rfcAad = hex("50515253c0c1c2c3c4c5c6c7")
    private val rfcPlaintext = hex(
        "4c616469657320616e642047656e746c656d656e206f662074686520636c617373206f66202739393a" +
            "204966204920636f756c64206f6666657220796f75206f6e6c79206f6e652074697020666f72207468" +
            "65206675747572652c2073756e73637265656e20776f756c642062652069742e",
    )
    private val rfcSealed = hex(
        "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282" +
            "fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab3" +
            "24e4fad675945585808b4831d7bc3ff4def08e4b7a9de576d26586cec64b6116" +
            "1ae10b594f09e26a7e902ecbd0600691",
    )

    // Conscrypt rejects IV reuse per (cipher, key) in encrypt mode; tests use fresh keys like
    // production sessions do, and counter nonces like the streams do.
    private val random = SecureRandom()
    private var nonceCounter = 0L

    private fun freshKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }
    private fun nextNonce(): ByteArray = AirPlayCrypto.nonce64(++nonceCounter)

    @Test
    fun `seal matches RFC 8439 vector on every engine`() {
        forEachEngine {
            assertArrayEquals(rfcSealed, AirPlayCrypto.chachaSeal(rfcKey, rfcNonce, rfcPlaintext, rfcAad))
        }
    }

    @Test
    fun `open reverses seal on every engine`() {
        val key = freshKey()
        val plaintexts = listOf(ByteArray(0), byteArrayOf(42), rfcPlaintext)
        forEachEngine {
            for (plaintext in plaintexts) {
                val nonce = nextNonce()
                val sealed = AirPlayCrypto.chachaSeal(key, nonce, plaintext, rfcAad)
                assertEquals(plaintext.size + 16, sealed.size)
                assertArrayEquals(plaintext, AirPlayCrypto.chachaOpen(key, nonce, sealed, rfcAad))
            }
        }
    }

    @Test
    fun `seal and open interoperate across engines`() {
        val key = freshKey()
        val nonce = nextNonce()
        AirPlayCrypto.aeadEngine = AirPlayCrypto.AeadEngine.BOUNCY_CASTLE
        val bcSealed = AirPlayCrypto.chachaSeal(key, nonce, rfcPlaintext, rfcAad)
        AirPlayCrypto.aeadEngine = AirPlayCrypto.AeadEngine.JCA
        assertArrayEquals(rfcPlaintext, AirPlayCrypto.chachaOpen(key, nonce, bcSealed, rfcAad))

        val secondNonce = nextNonce()
        val jcaSealed = AirPlayCrypto.chachaSeal(key, secondNonce, rfcPlaintext, rfcAad)
        AirPlayCrypto.aeadEngine = AirPlayCrypto.AeadEngine.BOUNCY_CASTLE
        assertArrayEquals(bcSealed, AirPlayCrypto.chachaSeal(key, nonce, rfcPlaintext, rfcAad))
        assertArrayEquals(rfcPlaintext, AirPlayCrypto.chachaOpen(key, secondNonce, jcaSealed, rfcAad))
    }

    @Test
    fun `tampered ciphertext fails on every engine`() {
        val key = freshKey()
        forEachEngine {
            val nonce = nextNonce()
            val sealed = AirPlayCrypto.chachaSeal(key, nonce, rfcPlaintext, rfcAad)
            sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()
            assertThrows(Exception::class.java) {
                AirPlayCrypto.chachaOpen(key, nonce, sealed, rfcAad)
            }
        }
    }

    @Test
    fun `auto detection prefers the platform JCA engine when available`() {
        AirPlayCrypto.redetectAeadEngine()
        assertEquals(AirPlayCrypto.AeadEngine.JCA, AirPlayCrypto.aeadEngine)
        assertTrue(AirPlayCrypto.isAeadAccelerated)
    }

    private fun forEachEngine(block: () -> Unit) {
        for (engine in AirPlayCrypto.AeadEngine.entries) {
            AirPlayCrypto.aeadEngine = engine
            block()
        }
    }
}
