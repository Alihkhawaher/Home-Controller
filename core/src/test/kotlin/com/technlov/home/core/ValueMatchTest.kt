package com.technlov.home.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValueMatchTest {
    private fun n(v: Double) = Value.Num(v)
    private fun t(v: String) = Value.Text(v)
    private fun b(v: Boolean) = Value.Bool(v)

    @Test
    fun numbersCompareNumericallyWithEveryOp() {
        assertTrue(n(30.0).matches(Op.EQ, n(30.0)))
        assertFalse(n(30.0).matches(Op.NE, n(30.0)))
        assertTrue(n(30.5).matches(Op.GT, n(30.0)))
        assertFalse(n(30.0).matches(Op.GT, n(30.0)))
        assertTrue(n(30.0).matches(Op.GE, n(30.0)))
        assertTrue(n(29.9).matches(Op.LT, n(30.0)))
        assertFalse(n(30.0).matches(Op.LT, n(30.0)))
        assertTrue(n(30.0).matches(Op.LE, n(30.0)))
        assertTrue(n(-5.0).matches(Op.LT, n(0.0)))
    }

    @Test
    fun nanIsNeverEqualOrOrdered() {
        assertFalse(n(Double.NaN).matches(Op.EQ, n(Double.NaN)))
        assertTrue(n(Double.NaN).matches(Op.NE, n(1.0)))
        assertFalse(n(Double.NaN).matches(Op.GE, n(1.0)))
        assertFalse(n(Double.NaN).matches(Op.LT, n(1.0)))
    }

    @Test
    fun textSupportsEqualityIgnoringCaseAndSpaces() {
        assertTrue(t("cool").matches(Op.EQ, t("Cool")))
        assertTrue(t(" cool ").matches(Op.EQ, t("cool")))
        assertFalse(t("cool").matches(Op.EQ, t("heat")))
        assertTrue(t("cool").matches(Op.NE, t("heat")))
        assertFalse(t("cool").matches(Op.NE, t("COOL")))
    }

    @Test
    fun textHasNoOrdering() {
        assertFalse(t("b").matches(Op.GT, t("a")))
        assertFalse(t("a").matches(Op.LT, t("b")))
        assertFalse(t("a").matches(Op.GE, t("a")))
        assertFalse(t("a").matches(Op.LE, t("a")))
    }

    @Test
    fun boolSupportsEqualityOnly() {
        assertTrue(b(true).matches(Op.EQ, b(true)))
        assertFalse(b(true).matches(Op.EQ, b(false)))
        assertTrue(b(true).matches(Op.NE, b(false)))
        assertFalse(b(true).matches(Op.GT, b(false)))
        assertFalse(b(false).matches(Op.LE, b(true)))
    }

    @Test
    fun numericTextComparesWithNumbers() {
        assertTrue(t("28").matches(Op.EQ, n(28.0)))
        assertTrue(n(28.0).matches(Op.EQ, t(" 28.0 ")))
        assertTrue(t("31").matches(Op.GE, n(30.0)))
        assertTrue(n(29.0).matches(Op.LT, t("30")))
    }

    @Test
    fun differentKindsAreNeverEqual() {
        assertFalse(n(1.0).matches(Op.EQ, b(true)))
        assertTrue(n(1.0).matches(Op.NE, b(true)))
        assertFalse(b(true).matches(Op.EQ, t("true")))
        assertTrue(b(true).matches(Op.NE, t("true")))
        assertFalse(t("hot").matches(Op.EQ, n(30.0)))
        assertTrue(t("hot").matches(Op.NE, n(30.0)))
        assertFalse(t("hot").matches(Op.GT, n(30.0)))
        assertFalse(n(1.0).matches(Op.GE, b(true)))
    }
}
