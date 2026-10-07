package com.example.agentcostcontrol.data;

import org.junit.Test;

import java.math.BigDecimal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class MoneyCodecTest {
    @Test
    public void preservesTheLargestSupportedPostgresAmountExactly() {
        BigDecimal amount = new BigDecimal("9999999999.99");

        assertEquals("9999999999.99", MoneyCodec.toJsonNumber(amount));
        assertEquals(amount, MoneyCodec.fromText("9999999999.99"));
    }

    @Test
    public void rejectsAmountsOutsideNumericTwelveTwo() {
        assertInvalid(new BigDecimal("10000000000.00"));
        assertInvalid(new BigDecimal("1E+12"));
        assertInvalid(new BigDecimal("0.001"));
        assertInvalid(new BigDecimal("-0.01"));
    }

    @Test
    public void parsesPostgrestTextCastWithoutBinaryFloatingPoint() {
        BigDecimal amount = MoneyCodec.fromText("1234567890.01");

        assertEquals("1234567890.01", amount.toPlainString());
    }

    private static void assertInvalid(BigDecimal amount) {
        try {
            MoneyCodec.toJsonNumber(amount);
            fail("Expected an invalid amount to be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected: callers should show the local validation message before sending the request.
        }
    }
}
