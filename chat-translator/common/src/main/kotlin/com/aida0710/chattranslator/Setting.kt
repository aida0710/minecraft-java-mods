package com.aida0710.chattranslator

/**
 * 設定ファイル (TOML) の 1 項目。[path] は "chat.enabled" のように表の名前を付けたキー。
 * ファイルの値が型や範囲に合わなければ [parse] は null を返し、既定値が使われる。
 */
sealed class Setting<T : Any>(val path: String, val default: T, private val description: List<String>) {
    /**
     * ファイルに書くコメント。範囲や選択肢は自動で書き足す
     * (サブクラスの範囲・選択肢はこのクラスの初期化時点ではまだ入っていないため、使うときに組み立てる)
     */
    val comment: String get() = (description + constraintNote()).joinToString("\n")

    /** ファイルから読んだ値を検証して変換する。合わなければ null */
    abstract fun parse(raw: Any?): T?

    /** ファイルに書く形 */
    open fun serialize(value: T): Any = value

    protected open fun constraintNote(): List<String> = emptyList()
}

class BooleanSetting(path: String, default: Boolean, vararg comment: String) : Setting<Boolean>(path, default, comment.toList()) {
    override fun parse(raw: Any?) = raw as? Boolean
}

class IntSetting(path: String, default: Int, private val range: IntRange, vararg comment: String) : Setting<Int>(path, default, comment.toList()) {
    override fun parse(raw: Any?): Int? {
        // TOML の整数は大きさによって Integer か Long で読まれる。小数は受け付けない
        if (raw !is Int && raw !is Long) return null
        return (raw as Number).toLong().takeIf { it in range.first..range.last }?.toInt()
    }

    override fun constraintNote() = listOf("Range: ${range.first} ~ ${range.last}")
}

class DoubleSetting(path: String, default: Double, private val range: ClosedFloatingPointRange<Double>, vararg comment: String) :
    Setting<Double>(path, default, comment.toList()) {
    override fun parse(raw: Any?) = (raw as? Number)?.toDouble()?.takeIf { it in range }

    override fun constraintNote() = listOf("Range: ${range.start} ~ ${range.endInclusive}")
}

class StringSetting(path: String, default: String, vararg comment: String) : Setting<String>(path, default, comment.toList()) {
    override fun parse(raw: Any?) = raw as? String
}

class EnumSetting<E : Enum<E>>(path: String, default: E, private val values: List<E>, vararg comment: String) :
    Setting<E>(path, default, comment.toList()) {
    override fun parse(raw: Any?) = (raw as? String)?.let { name -> values.firstOrNull { it.name.equals(name, ignoreCase = true) } }

    override fun serialize(value: E): Any = value.name

    override fun constraintNote() = listOf("Allowed Values: " + values.joinToString(", ") { it.name })
}

class StringListSetting(path: String, default: List<String>, vararg comment: String) : Setting<List<String>>(path, default, comment.toList()) {
    override fun parse(raw: Any?): List<String>? {
        val list = raw as? List<*> ?: return null
        return list.takeIf { items -> items.all { it is String } }?.map { it as String }
    }
}
