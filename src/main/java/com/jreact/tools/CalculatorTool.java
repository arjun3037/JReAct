package com.jreact.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Hand-written arithmetic evaluator exposed to the LLM as a tool.
 * Deliberately not using a scripting engine (e.g. Nashorn) — a small
 * recursive-descent parser keeps the whole mechanic visible and dependency-free.
 */
public class CalculatorTool {

    @Tool(description = "Evaluate a basic arithmetic expression and return the numeric result. "
            + "Supports +, -, *, /, parentheses and decimals, e.g. \"12 * (3 + 4)\" or \"(10 - 4) / 2\". "
            + "Always use this tool for arithmetic instead of computing it yourself.")
    public double calculate(
            @ToolParam(description = "The arithmetic expression to evaluate, e.g. \"2 + 2\"") String expression) {
        return new ExpressionParser(expression).parse();
    }

    /**
     * expression := term (('+' | '-') term)*
     * term       := factor (('*' | '/') factor)*
     * factor     := '-' factor | number | '(' expression ')'
     */
    private static final class ExpressionParser {
        private final String input;
        private int pos = 0;

        ExpressionParser(String input) {
            this.input = input;
        }

        double parse() {
            double result = parseExpression();
            skipWhitespace();
            if (pos != input.length()) {
                throw new IllegalArgumentException(
                        "Unexpected character '" + input.charAt(pos) + "' at position " + pos + " in expression: " + input);
            }
            return result;
        }

        private double parseExpression() {
            double value = parseTerm();
            while (true) {
                skipWhitespace();
                if (peek('+')) {
                    pos++;
                    value += parseTerm();
                } else if (peek('-')) {
                    pos++;
                    value -= parseTerm();
                } else {
                    return value;
                }
            }
        }

        private double parseTerm() {
            double value = parseFactor();
            while (true) {
                skipWhitespace();
                if (peek('*')) {
                    pos++;
                    value *= parseFactor();
                } else if (peek('/')) {
                    pos++;
                    double divisor = parseFactor();
                    if (divisor == 0) {
                        throw new ArithmeticException("Division by zero in expression: " + input);
                    }
                    value /= divisor;
                } else {
                    return value;
                }
            }
        }

        private double parseFactor() {
            skipWhitespace();
            if (peek('-')) {
                pos++;
                return -parseFactor();
            }
            if (peek('(')) {
                pos++;
                double value = parseExpression();
                skipWhitespace();
                if (!peek(')')) {
                    throw new IllegalArgumentException("Missing closing parenthesis in expression: " + input);
                }
                pos++;
                return value;
            }
            return parseNumber();
        }

        private double parseNumber() {
            skipWhitespace();
            int start = pos;
            while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException(
                        "Expected a number at position " + pos + " in expression: " + input);
            }
            return Double.parseDouble(input.substring(start, pos));
        }

        private void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }

        private boolean peek(char c) {
            return pos < input.length() && input.charAt(pos) == c;
        }
    }
}
