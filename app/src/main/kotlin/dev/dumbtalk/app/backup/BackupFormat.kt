package dev.dumbtalk.app.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The passphrase is wrong, or the file is damaged or cut short. */
class BackupUnreadable(message: String) : IOException(message)

/**
 * A DumbTalk backup: a zip of the app's data, encrypted with a key derived
 * from a passphrase (PBKDF2-HMAC-SHA256). After the header ("DTBACKUP1",
 * a 16-byte salt and the iteration count) the zip follows in chunks of up
 * to 1 MiB, each a 4-byte length (top bit set on the last chunk) and its
 * AES-256-GCM ciphertext. A chunk's nonce is the file's 4 random bytes and
 * its index; the index and the last-chunk flag are authenticated too, so
 * chunks can't be reordered, dropped or cut off without it showing.
 */
object BackupFormat {
    private val MAGIC = "DTBACKUP1".toByteArray()
    private const val CHUNK = 1 shl 20
    private const val LAST = 1 shl 31
    const val ITERATIONS = 310_000

    /** A stream to write the plain zip into; closing it finishes the file. */
    fun encrypt(out: OutputStream, passphrase: CharArray, iterations: Int = ITERATIONS): OutputStream {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val prefix = ByteArray(4).also(random::nextBytes)
        val data = DataOutputStream(out)
        data.write(MAGIC)
        data.write(salt)
        data.writeInt(iterations)
        data.write(prefix)
        return ChunkWriter(data, key(passphrase, salt, iterations), prefix)
    }

    /** The plain zip, checked chunk by chunk as it's read. */
    fun decrypt(input: InputStream, passphrase: CharArray): InputStream {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        runCatching { data.readFully(magic) }
        if (!magic.contentEquals(MAGIC)) throw BackupUnreadable("This isn't a DumbTalk backup")
        val salt = ByteArray(16).also(data::readFully)
        val iterations = data.readInt()
        if (iterations !in 1..10_000_000) throw BackupUnreadable("This backup is damaged")
        val prefix = ByteArray(4).also(data::readFully)
        return ChunkReader(data, key(passphrase, salt, iterations), prefix)
    }

    private fun key(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun cipher(mode: Int, key: SecretKeySpec, prefix: ByteArray, index: Long, last: Boolean): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(128, ByteBuffer.allocate(12).put(prefix).putLong(index).array()))
            updateAAD(ByteBuffer.allocate(9).putLong(index).put(if (last) 1 else 0).array())
        }

    private class ChunkWriter(private val out: DataOutputStream, private val key: SecretKeySpec, private val prefix: ByteArray) :
        FilterOutputStream(out) {
        private val buffer = ByteArray(CHUNK)
        private var filled = 0
        private var index = 0L
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var from = off
            var left = len
            while (left > 0) {
                // A full buffer is only written once more follows, so the last chunk is known when closing.
                if (filled == CHUNK) flushChunk(last = false)
                val n = minOf(left, CHUNK - filled)
                System.arraycopy(b, from, buffer, filled, n)
                filled += n
                from += n
                left -= n
            }
        }

        private fun flushChunk(last: Boolean) {
            val sealed = cipher(Cipher.ENCRYPT_MODE, key, prefix, index++, last).doFinal(buffer, 0, filled)
            out.writeInt(sealed.size or if (last) LAST else 0)
            out.write(sealed)
            filled = 0
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            closed = true
            flushChunk(last = true)
            out.close()
        }
    }

    private class ChunkReader(private val input: DataInputStream, private val key: SecretKeySpec, private val prefix: ByteArray) :
        FilterInputStream(input) {
        private var chunk = ByteArray(0)
        private var position = 0
        private var index = 0L
        private var finished = false

        private fun next(): Boolean {
            if (finished) return false
            val header = try {
                input.readInt()
            } catch (_: EOFException) {
                throw BackupUnreadable("This backup is cut short")
            }
            val last = header and LAST != 0
            val size = header and LAST.inv()
            if (size !in 16..CHUNK + 16) throw BackupUnreadable("This backup is damaged")
            val sealed = ByteArray(size)
            try {
                input.readFully(sealed)
            } catch (_: EOFException) {
                throw BackupUnreadable("This backup is cut short")
            }
            chunk = try {
                cipher(Cipher.DECRYPT_MODE, key, prefix, index++, last).doFinal(sealed)
            } catch (_: AEADBadTagException) {
                throw BackupUnreadable(if (index == 1L) "Wrong passphrase, or not a backup made with it" else "This backup is damaged")
            }
            position = 0
            finished = last
            return true
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (position == chunk.size) if (!next()) return -1
            val n = minOf(len, chunk.size - position)
            System.arraycopy(chunk, position, b, off, n)
            position += n
            return n
        }

        override fun available() = chunk.size - position
        override fun markSupported() = false
    }

    /**
     * Zips [roots] (named directories under [base]) into [out], leaving out
     * any path [skip] says to. [progress] gets the bytes written so far.
     */
    fun writeArchive(out: OutputStream, base: File, roots: List<String>, skip: (String) -> Boolean, progress: (Long) -> Unit = {}) {
        var written = 0L
        ZipOutputStream(out).use { zip ->
            for (root in roots) {
                val dir = File(base, root)
                if (!dir.exists()) continue
                dir.walkTopDown().filter { it.isFile }.forEach { file ->
                    val name = file.relativeTo(base).invariantSeparatorsPath
                    if (skip(name)) return@forEach
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            zip.write(buffer, 0, n)
                            written += n
                            progress(written)
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * Unzips into [dest], refusing any entry that would land outside it. The
     * rest of [input] is read too, so a backup cut short after its last file
     * still fails.
     */
    fun readArchive(input: InputStream, dest: File, progress: (Long) -> Unit = {}) {
        val root = dest.canonicalFile
        var read = 0L
        val zip = ZipInputStream(input)
        input.use {
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(root, entry.name).canonicalFile
                if (!target.path.startsWith(root.path + File.separator)) throw BackupUnreadable("This backup is damaged")
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        progress(read)
                    }
                }
            }
            val rest = ByteArray(64 * 1024)
            while (input.read(rest) >= 0) Unit
        }
    }
}
