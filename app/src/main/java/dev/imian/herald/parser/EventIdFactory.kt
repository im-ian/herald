package dev.imian.herald.parser

import java.security.MessageDigest

internal object EventIdFactory {
    fun messaging(
        sourcePackage: String,
        notificationKey: String,
        sender: String?,
        text: String?,
        sentAt: Long?,
        attachmentMimeType: String?,
        occurrence: Int,
    ): String = digest(
        "message-v1",
        sourcePackage,
        notificationKey,
        sender,
        text,
        sentAt?.toString(),
        attachmentMimeType,
        occurrence.toString(),
    )

    fun fallback(
        sourcePackage: String,
        notificationKey: String,
        postedAt: Long,
        title: String?,
        text: String,
        method: String,
    ): String = digest(
        "fallback-v1",
        sourcePackage,
        notificationKey,
        postedAt.toString(),
        title,
        text,
        method,
    )

    private fun digest(vararg fields: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach { field ->
            val bytes = field.orEmpty().toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
            digest.update(0)
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
