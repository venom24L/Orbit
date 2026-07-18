package com.example

object ExpressionEvaluator {

    fun evaluate(expression: String): Double {
        val sanitized = expression.replace(" ", "")
        if (sanitized.isEmpty()) {
            throw IllegalArgumentException("Empty expression")
        }
        val parser = Parser(sanitized)
        val result = parser.parseExpression()
        if (parser.index < sanitized.length) {
            throw IllegalArgumentException("Unexpected character at index ${parser.index}")
        }
        return result
    }

    private class Parser(val input: String) {
        var index = 0

        fun parseExpression(): Double {
            var value = parseTerm()
            while (index < input.length) {
                val char = input[index]
                if (char == '+' || char == '-') {
                    index++
                    val right = parseTerm()
                    if (char == '+') {
                        value += right
                    } else {
                        value -= right
                    }
                } else {
                    break
                }
            }
            return value
        }

        fun parseTerm(): Double {
            var value = parseFactor()
            while (index < input.length) {
                val char = input[index]
                if (char == '*' || char == '/') {
                    index++
                    val right = parseFactor()
                    if (char == '*') {
                        value *= right
                    } else {
                        if (right == 0.0) {
                            throw ArithmeticException("Division by zero")
                        }
                        value /= right
                    }
                } else {
                    break
                }
            }
            return value
        }

        fun parseFactor(): Double {
            if (index >= input.length) {
                throw IllegalArgumentException("Unexpected end of expression")
            }
            val char = input[index]
            if (char == '+') {
                index++
                return parseFactor()
            }
            if (char == '-') {
                index++
                return -parseFactor()
            }
            if (char == '(') {
                index++
                val value = parseExpression()
                if (index >= input.length || input[index] != ')') {
                    throw IllegalArgumentException("Missing closing parenthesis")
                }
                index++ // Consume ')'
                return value
            }
            // Parse a number
            val start = index
            while (index < input.length) {
                val c = input[index]
                if (c.isDigit() || c == '.') {
                    index++
                } else {
                    break
                }
            }
            if (start == index) {
                throw IllegalArgumentException("Expected number or parenthesis")
            }
            val numberStr = input.substring(start, index)
            return numberStr.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid number format")
        }
    }
}
