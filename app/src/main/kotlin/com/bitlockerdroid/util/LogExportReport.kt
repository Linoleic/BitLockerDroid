package com.bitlockerdroid.util

/** Builds a chronological UTF-8 report from app-owned logs, never preferences or disk data. */
internal object LogExportReport {
    data class Environment(val version: String, val android: String, val device: String, val mode: String, val time: String)

    fun create(environment: Environment, snapshot: LogSnapshot): String {
        val redactor = LogExportRedactor()
        return buildString {
            appendLine("BitUnlocker diagnostic log")
            appendLine("Exported: ${environment.time}")
            appendLine("Application: ${environment.version}")
            appendLine("Android: ${environment.android}")
            appendLine("Device: ${environment.device}")
            appendLine("Mode: ${environment.mode}")
            appendLine("Scope: rolling application log and recent crash log; no system logcat capture.")
            appendLine("Known sensitive fields are hidden. Review before sharing; free-form messages may contain other personal information.")
            snapshot.notes.forEach { appendLine("Note: $it") }
            appendLine()
            appendLine("=== Application log (oldest first, up to 512 KiB) ===")
            appendLine(redactor.redact(snapshot.application?.takeIf { it.isNotBlank() } ?: "[No application log]"))
            appendLine("=== Recent crash log (up to 256 KiB) ===")
            appendLine(redactor.redact(snapshot.crash?.takeIf { it.isNotBlank() } ?: "[No crash log]"))
        }
    }
}

/** Targeted rules preserve transport nodes, USB IDs, error codes and stack locations. */
internal class LogExportRedactor {
    private val aliases = mutableMapOf<Pair<String, String>, String>()
    private fun alias(kind: String, value: String): String = aliases.getOrPut(kind to value) {
        "[$kind#${aliases.keys.count { it.first == kind } + 1}]"
    }

    fun redact(raw: String): String {
        var text = raw
        text = SECRET.replace(text) { "${it.groupValues[1]}=[hidden]" }
        text = RECOVERY_KEY.replace(text, "[recovery-key]")
        text = UUID.replace(text) { alias("id", it.value.lowercase()) }
        text = SERIAL.replace(text) { "${it.groupValues[1]}=${alias("serial", it.groupValues[2])}" }
        text = URI.replace(text) { alias("uri", it.value) }
        text = PRIVATE_TEXT.replace(text) { "${it.groupValues[1]}=${alias("value", it.groupValues[2])}" }
        text = ARG.replace(text) { match ->
            val value = match.groupValues[2]
            val safeNode = value.matches(Regex("(?:/dev/(?:block|bus/usb)/[\\w/.:_-]+|usb://\\d+(?:/p?\\d+)?)"))
            "${match.groupValues[1]}=" + if (safeNode) value else alias("value", value)
        }
        text = REFERENCE.replace(text) { "${it.groupValues[1]}=${alias("value", it.groupValues[2])}" }
        text = CLOSE_LABEL.replace(text) { "${it.groupValues[1]}[label], guid=" }
        text = UNENCRYPTED_LABEL.replace(text, "volume -> [label] (")
        text = REJECTED_NAME.replace(text, "renameDocument: rejecting internal backup/rollback name: [name]")
        text = PATH.replace(text) { match ->
            if (match.value.startsWith("/dev/block/") || match.value.startsWith("/dev/bus/usb/") ||
                match.value.startsWith("/sys/") || match.value.startsWith("/proc/")) match.value
            else alias("path", match.value.trimEnd()) + match.value.takeLastWhile { it.isWhitespace() }
        }
        return text
    }

    companion object {
        private val SECRET = Regex("""(?i)\b(password|passwd|recovery[_ -]?key|vmk|fvek|access[_ -]?token|authorization)\s*[=:]\s*[^\r\n]*""")
        private val RECOVERY_KEY = Regex("""(?<!\d)\d{6}(?:[- ]?\d{6}){7}(?!\d)""")
        private val UUID = Regex("""(?i)\b[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\b""")
        private val SERIAL = Regex("""(?i)\b(serial(?:number)?|volumeSerial|sn)\s*[=:]\s*([^\r\n,;)]+)""")
        private val URI = Regex("""(?:content|file)://[^\s"'<>]+""")
        private val PRIVATE_TEXT = Regex("""\b(displayName|query)=([^\r\n]*)""")
        private val ARG = Regex("""\b(arg)=([^\r\n]*)""")
        private val REFERENCE = Regex("""\b(docId|rootId|parent|child)=([^\s,;)]+)""")
        private val CLOSE_LABEL = Regex("""(closing (?:/dev/\S+|usb://\S+) \()[^\r\n,]*, guid=""")
        private val UNENCRYPTED_LABEL = Regex("""volume -> [^\r\n(]*\(""")
        private val REJECTED_NAME = Regex("""renameDocument: rejecting internal backup/rollback name: [^\r\n]*""")
        private val PATH = Regex("""(?<![\w:/])/(?:dev/(?:block|bus/usb)|sys|proc)(?:/[\w.:-]+)*|(?<![\w:/])/(?!/)[^\r\n,;"'<>():]*""")
    }
}
