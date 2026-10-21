package com.gamehub.downloader.data

import android.content.ComponentName
import android.content.Intent
import android.net.Uri

/**
 * Turns a Daijishou / Cocoon FE "player" launch line (`am start` arguments such as
 * `-n pkg/.Activity -a android.intent.action.VIEW -d {file.uri} --activity-clear-task`)
 * into an [Intent], filling in {file.path}, {file.uri}, {file.mime}, {file.name} and {tags.…}.
 */
object AmStart {

    /** Splits on whitespace, keeping "quoted strings" together. */
    fun tokenize(args: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var any = false
        for (c in args) {
            when {
                quote != null && c == quote -> quote = null
                quote == null && (c == '"' || c == '\'') -> { quote = c; any = true }
                quote == null && c.isWhitespace() -> {
                    if (cur.isNotEmpty() || any) out += cur.toString()
                    cur.clear(); any = false
                }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty() || any) out += cur.toString()
        return out
    }

    private val PLACEHOLDER = Regex("""\{([a-zA-Z_.]+)\}""")

    /** Replaces placeholders; null when one is unknown (the game can't be started this way). */
    fun fill(token: String, values: Map<String, String>): String? {
        var missing = false
        val s = PLACEHOLDER.replace(token) { m -> values[m.groupValues[1]] ?: run { missing = true; m.value } }
        return if (missing) null else s
    }

    /**
     * Builds the intent, or returns null when the line has no target (-n) or needs a value we
     * don't have (e.g. {file.path} for a game that isn't on local storage).
     */
    fun toIntent(args: String, values: Map<String, String>): Intent? {
        val tokens = tokenize(args)
        val intent = Intent()
        var data: Uri? = null
        var type: String? = null
        var i = 0
        fun next(): String? = tokens.getOrNull(++i)
        fun value(): String? = next()?.let { fill(it, values) }
        while (i < tokens.size) {
            when (val t = tokens[i]) {
                "-n" -> {
                    val comp = next() ?: return null
                    val pkg = comp.substringBefore('/')
                    var cls = comp.substringAfter('/', "")
                    if (cls.startsWith('.')) cls = pkg + cls
                    if (pkg.isEmpty() || cls.isEmpty()) return null
                    intent.component = ComponentName(pkg, cls)
                }
                "-a" -> intent.action = next() ?: return null
                "-d" -> data = Uri.parse(value() ?: return null)
                "-t" -> type = value() ?: return null
                "-c" -> intent.addCategory(next() ?: return null)
                "-e", "--es" -> { val k = next() ?: return null; intent.putExtra(k, value() ?: return null) }
                "--ez" -> {
                    val k = next() ?: return null
                    val v = next() ?: return null
                    intent.putExtra(k, v.equals("true", ignoreCase = true) || v == "1")
                }
                "--ei" -> { val k = next() ?: return null; intent.putExtra(k, next()?.toIntOrNull() ?: return null) }
                "--el" -> { val k = next() ?: return null; intent.putExtra(k, next()?.toLongOrNull() ?: return null) }
                "--ef" -> { val k = next() ?: return null; intent.putExtra(k, next()?.toFloatOrNull() ?: return null) }
                "--eu" -> { val k = next() ?: return null; intent.putExtra(k, Uri.parse(value() ?: return null)) }
                "--esa" -> { val k = next() ?: return null; intent.putExtra(k, (value() ?: return null).split(',').toTypedArray()) }
                "--eia" -> { val k = next() ?: return null; intent.putExtra(k, (next() ?: return null).split(',').mapNotNull { it.trim().toIntOrNull() }.toIntArray()) }
                "--activity-clear-task" -> intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                "--activity-clear-top" -> intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                "--activity-no-history" -> intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                "--activity-new-task" -> intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                "--activity-single-top" -> intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                "--grant-read-uri-permission" -> intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                "--grant-write-uri-permission" -> intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                else -> if (t.startsWith("-")) Unit // flags we don't need (-W, -S, --user …) are skipped
            }
            i++
        }
        if (intent.component == null) return null
        when {
            data != null && type != null -> intent.setDataAndType(data, type)
            data != null -> intent.data = data
            type != null -> intent.type = type
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
