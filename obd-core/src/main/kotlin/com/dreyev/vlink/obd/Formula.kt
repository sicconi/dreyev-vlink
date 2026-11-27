package com.dreyev.vlink.obd

/**
 * Tiny expression evaluator for manufacturer PID formulas, in the familiar
 * "Torque" style: bytes after the response prefix are A, B, C, D … (A = first).
 *
 * Supports + - * / % & | ^ << >>, parentheses, decimal and 0x hex literals, and
 * functions: signed(x, bits), bit(x, n), min(a,b), max(a,b), abs(x).
 * Examples: "(A*256+B)/4", "signed(A*256+B,16)/10", "bit(C,4)", "A-40".
 */
class Formula(val text: String) {
    private val tokens = tokenize(text)

    fun eval(bytes: IntArray): Double {
        val p = Parser(tokens, bytes)
        val v = p.expr()
        require(p.atEnd()) { "Unexpected '${p.peek()}' in formula: $text" }
        return v
    }

    /** Highest byte variable used (to check the reply is long enough). */
    val bytesNeeded: Int = tokens.filter { it.length == 1 && it[0] in 'A'..'Z' }.maxOfOrNull { it[0] - 'A' + 1 } ?: 0

    private class Parser(val t: List<String>, val bytes: IntArray) {
        var i = 0
        fun atEnd() = i >= t.size
        fun peek() = t.getOrNull(i)
        fun next() = t[i++]
        fun expect(s: String) { require(next() == s) { "Expected $s" } }

        // precedence: | ^ & << >> + - * / %
        fun expr(): Double = or()
        fun or(): Double { var v = xor(); while (peek() == "|") { next(); v = (v.toLong() or xor().toLong()).toDouble() }; return v }
        fun xor(): Double { var v = and(); while (peek() == "^") { next(); v = (v.toLong() xor and().toLong()).toDouble() }; return v }
        fun and(): Double { var v = shift(); while (peek() == "&") { next(); v = (v.toLong() and shift().toLong()).toDouble() }; return v }
        fun shift(): Double {
            var v = add()
            while (peek() == "<<" || peek() == ">>") {
                val op = next(); val r = add().toInt()
                v = if (op == "<<") (v.toLong() shl r).toDouble() else (v.toLong() shr r).toDouble()
            }
            return v
        }
        fun add(): Double {
            var v = mul()
            while (peek() == "+" || peek() == "-") { if (next() == "+") v += mul() else v -= mul() }
            return v
        }
        fun mul(): Double {
            var v = unary()
            while (peek() == "*" || peek() == "/" || peek() == "%") {
                when (next()) { "*" -> v *= unary(); "/" -> v /= unary(); else -> v %= unary() }
            }
            return v
        }
        fun unary(): Double = if (peek() == "-") { next(); -unary() } else atom()
        fun atom(): Double {
            val tok = next()
            return when {
                tok == "(" -> expr().also { expect(")") }
                tok.startsWith("0x", true) -> tok.substring(2).toLong(16).toDouble()
                tok[0].isDigit() || tok[0] == '.' -> tok.toDouble()
                tok.length == 1 && tok[0] in 'A'..'Z' -> {
                    val idx = tok[0] - 'A'
                    require(idx < bytes.size) { "Reply too short for $tok" }
                    bytes[idx].toDouble()
                }
                else -> {
                    expect("(")
                    val args = mutableListOf(expr())
                    while (peek() == ",") { next(); args += expr() }
                    expect(")")
                    call(tok.lowercase(), args)
                }
            }
        }
        fun call(name: String, a: List<Double>): Double = when (name) {
            "signed" -> { val bits = a[1].toInt(); val v = a[0].toLong(); val m = 1L shl (bits - 1); ((v xor m) - m).toDouble() }
            "bit" -> ((a[0].toLong() shr a[1].toInt()) and 1L).toDouble()
            "min" -> minOf(a[0], a[1])
            "max" -> maxOf(a[0], a[1])
            "abs" -> kotlin.math.abs(a[0])
            else -> error("Unknown function $name")
        }
    }

    companion object {
        fun tokenize(s: String): List<String> {
            val out = mutableListOf<String>()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() -> i++
                    s.startsWith("<<", i) || s.startsWith(">>", i) -> { out += s.substring(i, i + 2); i += 2 }
                    c in "+-*/%&|^(),"  -> { out += c.toString(); i++ }
                    s.startsWith("0x", i, true) -> {
                        var j = i + 2; while (j < s.length && s[j].isLetterOrDigit()) j++
                        out += s.substring(i, j); i = j
                    }
                    c.isDigit() || c == '.' -> {
                        var j = i; while (j < s.length && (s[j].isDigit() || s[j] == '.')) j++
                        out += s.substring(i, j); i = j
                    }
                    c.isLetter() -> {
                        var j = i; while (j < s.length && s[j].isLetterOrDigit()) j++
                        out += s.substring(i, j); i = j
                    }
                    else -> error("Bad character '$c' in formula")
                }
            }
            return out
        }
    }
}
