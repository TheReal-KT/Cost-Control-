package com.example.agentcostcontrol.ui;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Currency-aware presentation for already-calculated amounts. */
public final class MoneyText {
    private MoneyText() { }

    public static String format(BigDecimal amount, String currencyCode) {
        if (amount == null) return "—";
        String code = currencyCode == null ? "" : currencyCode.toUpperCase(Locale.ROOT);
        DecimalFormat number = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        number.setRoundingMode(RoundingMode.HALF_UP);
        String symbol = symbolFor(code);
        return (symbol.isEmpty() ? code : symbol) + " " + number.format(amount);
    }

    private static String symbolFor(String code) {
        switch (code) {
            case "ZAR": return "R";
            case "USD": return "$";
            case "EUR": return "€";
            case "GBP": return "£";
            case "JPY": return "¥";
            case "CAD": return "CA$";
            case "AUD": return "A$";
            default: return "";
        }
    }
}
