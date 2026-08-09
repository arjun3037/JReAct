package com.jreact.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalculatorToolTest {

    private final CalculatorTool calculator = new CalculatorTool();

    @Test
    void addsSimpleExpression() {
        assertThat(calculator.calculate("2 + 2")).isEqualTo(4.0);
    }

    @Test
    void respectsOperatorPrecedenceAndParentheses() {
        assertThat(calculator.calculate("12 * (3 + 4)")).isEqualTo(84.0);
        assertThat(calculator.calculate("(10 - 4) / 2")).isEqualTo(3.0);
    }

    @Test
    void handlesNegativeNumbers() {
        assertThat(calculator.calculate("-5 + 10")).isEqualTo(5.0);
    }

    @Test
    void throwsOnDivisionByZero() {
        assertThatThrownBy(() -> calculator.calculate("10 / 0"))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void throwsOnMalformedExpression() {
        assertThatThrownBy(() -> calculator.calculate("2 +"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
