package com.technlov.home.core

/**
 * Compares a device value with [other] (the value written in an automation): `this <op> other`.
 *
 *  - Numbers compare numerically with every [Op]. A [Value.Text] that holds a number ("28") counts as that number
 *    when the other side is a [Value.Num].
 *  - [Value.Bool] and [Value.Text] support [Op.EQ] and [Op.NE] only; text compares trimmed and ignoring case
 *    ("Cool" == "cool"). The ordering ops are false for them.
 *  - Values of different kinds are never equal: EQ is false, NE is true, the ordering ops are false.
 */
fun Value.matches(op: Op, other: Value): Boolean {
    val a = numberOf(this, other)
    val b = numberOf(other, this)
    if (a != null && b != null) {
        return when (op) {
            Op.EQ -> a == b
            Op.NE -> a != b
            Op.GT -> a > b
            Op.GE -> a >= b
            Op.LT -> a < b
            Op.LE -> a <= b
        }
    }
    val equal: Boolean? = when {
        this is Value.Bool && other is Value.Bool -> v == other.v
        this is Value.Text && other is Value.Text -> v.trim().equals(other.v.trim(), ignoreCase = true)
        else -> null // different kinds
    }
    return when (op) {
        Op.EQ -> equal == true
        Op.NE -> equal != true
        else -> false
    }
}

/** [v] as a number when it is one, or when it is numeric text compared against a number. */
private fun numberOf(v: Value, counterpart: Value): Double? = when (v) {
    is Value.Num -> if (counterpart is Value.Num || counterpart is Value.Text) v.v else null
    is Value.Text -> if (counterpart is Value.Num) v.v.trim().toDoubleOrNull() else null
    is Value.Bool -> null
}
