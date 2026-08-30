package app.gamenative.stubapk

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.InvalidKeyException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.PrivateKey
import java.security.Security
import java.security.Signature

/**
 * Signs a generated APK with APK Signature Scheme v2.
 *
 * v2 rather than the older JAR signing because JAR signing needs a PKCS#7 SignedData, which the
 * platform's crypto stack cannot build -- v2 is a plain signature over the archive's bytes, so
 * everything it needs is a digest and a Signature. That costs nothing here: v2 has been accepted
 * since API 24, well below anything this runs on.
 *
 * The scheme digests the archive in three sections and inserts a block of its own between the
 * entries and the central directory, which is why the directory offset in the end-of-directory
 * record has to be rewritten afterwards.
 *
 * Reference: source.android.com/security/apksigning/v2.
 */
internal object ApkSigner {

    private const val BLOCK_MAGIC = "APK Sig Block 42"
    private const val V2_BLOCK_ID = 0x7109871a
    private const val CHUNK_SIZE = 1024 * 1024

    /** RSASSA-PKCS1-v1_5 with SHA2-256, the scheme's most widely supported algorithm. */
    private const val SIGNATURE_ALGORITHM_ID = 0x0103
    private const val SIGNATURE_ALGORITHM = "SHA256withRSA"

    private const val EOCD_SIGNATURE = 0x06054b50

    /**
     * Returns [apk] signed with [privateKey], whose public half [certificate] must carry.
     *
     * [apk] must have no signing block already, which is the case for anything this package
     * produces.
     */
    /**
     * A signer initialised with [key], from whichever installed provider will accept it.
     *
     * Tried in turn rather than left to Signature's own search, which stops at the first provider
     * offering the algorithm: a key the keystore holds is not an RSAPrivateKey, the bundled Bouncy
     * Castle rejects it outright on that basis, and anything else in the process can put Bouncy
     * Castle first by installing it. Naming a provider instead does not work either -- the
     * keystore's own signer lives under a different provider name than its keys do.
     */
    private fun signerFor(key: PrivateKey): Signature {
        var rejection: Exception? = null

        for (provider in Security.getProviders("Signature.$SIGNATURE_ALGORITHM").orEmpty()) {
            val candidate = runCatching { Signature.getInstance(SIGNATURE_ALGORITHM, provider) }.getOrNull()
                ?: continue
            try {
                candidate.initSign(key)
                return candidate
            } catch (rejected: InvalidKeyException) {
                rejection = rejected
            }
        }

        throw rejection ?: NoSuchAlgorithmException("no provider will sign with this key")
    }

    fun sign(apk: ByteArray, privateKey: PrivateKey, certificate: ByteArray): ByteArray {
        val eocdOffset = findEocd(apk)
        val directoryOffset = int(apk, eocdOffset + 16)

        val contents = apk.copyOfRange(0, directoryOffset)
        val directory = apk.copyOfRange(directoryOffset, eocdOffset)
        val eocd = apk.copyOfRange(eocdOffset, apk.size)

        // The digest has to be of the archive as it will finally be laid out, where the directory
        // has moved along by the size of the block being computed. The scheme resolves that
        // circularity by digesting the record with the offset it had before insertion.
        val digest = digest(contents, directory, eocd)

        val signedData = signedData(digest, certificate)
        val signature = signerFor(privateKey).run {
            update(signedData)
            sign()
        }

        val block = signingBlock(signedData, signature, certificate)

        val out = Le()
        out.bytes(contents)
        out.bytes(block)
        out.bytes(directory)
        out.bytes(patchEocd(eocd, directoryOffset + block.size))
        return out.toByteArray()
    }

    /**
     * The digest of the three sections, each chunked at a megabyte.
     *
     * Chunking is what lets a verifier check parts of a large APK independently; the prefixes
     * keep a chunk digest from being mistaken for a whole-file one.
     */
    private fun digest(vararg sections: ByteArray): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        for (section in sections) {
            var offset = 0
            while (offset < section.size) {
                val length = minOf(CHUNK_SIZE, section.size - offset)
                val sha = MessageDigest.getInstance("SHA-256")
                sha.update(0xa5.toByte())
                sha.update(le32(length))
                sha.update(section, offset, length)
                chunks += sha.digest()
                offset += length
            }
        }

        val sha = MessageDigest.getInstance("SHA-256")
        sha.update(0x5a.toByte())
        sha.update(le32(chunks.size))
        for (chunk in chunks) sha.update(chunk)
        return sha.digest()
    }

    /** What is actually signed: the digest, the certificate, and no extra attributes. */
    private fun signedData(digest: ByteArray, certificate: ByteArray): ByteArray {
        val digests = lengthPrefixed(
            lengthPrefixed(le32(SIGNATURE_ALGORITHM_ID) + lengthPrefixed(digest)),
        )
        val certificates = lengthPrefixed(lengthPrefixed(certificate))
        val attributes = lengthPrefixed(ByteArray(0))
        return digests + certificates + attributes
    }

    private fun signingBlock(signedData: ByteArray, signature: ByteArray, certificate: ByteArray): ByteArray {
        val signatures = lengthPrefixed(
            lengthPrefixed(le32(SIGNATURE_ALGORITHM_ID) + lengthPrefixed(signature)),
        )
        // The key as a SubjectPublicKeyInfo, which is how the certificate already carries it.
        val publicKey = lengthPrefixed(subjectPublicKeyInfo(certificate))

        val signer = lengthPrefixed(lengthPrefixed(signedData) + signatures + publicKey)
        val v2Block = lengthPrefixed(signer)

        // A pair is a 64 bit length, an id, and the value; the block repeats its own size at both
        // ends so it can be found by walking back from the end of the archive.
        val pair = Le()
        pair.u32(v2Block.size + 4)
        pair.u32(0) // the length is 64 bit, and never needs the high word
        pair.u32(V2_BLOCK_ID)
        pair.bytes(v2Block)
        val pairBytes = pair.toByteArray()

        val size = pairBytes.size + 8 + BLOCK_MAGIC.length
        val out = Le()
        out.u32(size)
        out.u32(0)
        out.bytes(pairBytes)
        out.u32(size)
        out.u32(0)
        out.bytes(BLOCK_MAGIC.toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    /**
     * Pulls the SubjectPublicKeyInfo out of a certificate.
     *
     * It is the seventh field of the TBSCertificate, and rather than walk the structure the
     * platform's own parser is used, which returns the key in exactly that encoding.
     */
    private fun subjectPublicKeyInfo(certificate: ByteArray): ByteArray {
        val factory = java.security.cert.CertificateFactory.getInstance("X.509")
        val parsed = factory.generateCertificate(certificate.inputStream())
        return parsed.publicKey.encoded
    }

    /** Prefixes [value] with its own length, which is how the scheme delimits everything. */
    private fun lengthPrefixed(value: ByteArray): ByteArray = le32(value.size) + value

    private fun le32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun int(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    /** The record's copy of where the central directory starts, now that it has moved. */
    private fun patchEocd(eocd: ByteArray, directoryOffset: Int): ByteArray =
        eocd.copyOf().also { le32(directoryOffset).copyInto(it, 16) }

    /**
     * Finds the end-of-directory record, which has no fixed position because of the comment it
     * may carry. Searched from the end, as a reader does.
     */
    private fun findEocd(apk: ByteArray): Int {
        val earliest = maxOf(0, apk.size - 0xffff - 22)
        for (offset in apk.size - 22 downTo earliest) {
            if (int(apk, offset) == EOCD_SIGNATURE) return offset
        }
        error("not a zip archive: no end of central directory record")
    }
}
