package com.technicallyunavailable.measure;

import java.util.Locale;

public final class MeasurementUtils {
    private MeasurementUtils() {}

    public enum UnitMode {
        FEET_INCHES("ft/in"), INCHES("in"), MILLIMETERS("mm"), CENTIMETERS("cm"), METERS("m");
        public final String label;
        UnitMode(String label) { this.label = label; }
    }

    public static String formatLength(double meters, UnitMode unit) {
        switch (unit) {
            case FEET_INCHES: return formatFeetInches(meters);
            case INCHES: return formatFractionalInches(meters * 39.37007874015748);
            case MILLIMETERS: return String.format(Locale.US, "%.0f mm", meters * 1000.0);
            case CENTIMETERS: return String.format(Locale.US, "%.1f cm", meters * 100.0);
            case METERS:
            default: return String.format(Locale.US, "%.3f m", meters);
        }
    }

    public static String formatArea(double squareMeters, UnitMode unit) {
        switch (unit) {
            case FEET_INCHES:
                return String.format(Locale.US, "%.2f ft²", squareMeters * 10.7639104167);
            case INCHES:
                return String.format(Locale.US, "%.1f in²", squareMeters * 1550.0031000062);
            case MILLIMETERS:
                return String.format(Locale.US, "%.0f mm²", squareMeters * 1_000_000.0);
            case CENTIMETERS:
                return String.format(Locale.US, "%.1f cm²", squareMeters * 10_000.0);
            case METERS:
            default:
                return String.format(Locale.US, "%.3f m²", squareMeters);
        }
    }

    public static double calibrationInputToMeters(double value, UnitMode unit) {
        switch (unit) {
            case FEET_INCHES:
            case INCHES:
                return value / 39.37007874015748;
            case MILLIMETERS:
                return value / 1000.0;
            case CENTIMETERS:
                return value / 100.0;
            case METERS:
            default:
                return value;
        }
    }

    public static String calibrationPromptUnit(UnitMode unit) {
        switch (unit) {
            case FEET_INCHES:
            case INCHES: return "decimal inches";
            case MILLIMETERS: return "millimeters";
            case CENTIMETERS: return "centimeters";
            case METERS:
            default: return "meters";
        }
    }

    private static String formatFeetInches(double meters) {
        double totalInches = meters * 39.37007874015748;
        long feet = (long) Math.floor(totalInches / 12.0);
        double inches = totalInches - feet * 12.0;
        int sixteenths = (int) Math.round(inches * 16.0);
        if (sixteenths >= 12 * 16) {
            feet += 1;
            sixteenths -= 12 * 16;
        }
        int wholeInches = sixteenths / 16;
        int frac = sixteenths % 16;
        String fraction = simplifyFraction(frac, 16);
        if (fraction.isEmpty()) {
            return String.format(Locale.US, "%d' %d\"", feet, wholeInches);
        }
        return String.format(Locale.US, "%d' %d %s\"", feet, wholeInches, fraction);
    }

    private static String formatFractionalInches(double inches) {
        int sixteenths = (int) Math.round(inches * 16.0);
        int whole = sixteenths / 16;
        int frac = Math.abs(sixteenths % 16);
        String fraction = simplifyFraction(frac, 16);
        if (fraction.isEmpty()) return whole + "\"";
        return whole + " " + fraction + "\"";
    }

    private static String simplifyFraction(int numerator, int denominator) {
        if (numerator == 0) return "";
        int gcd = gcd(Math.abs(numerator), denominator);
        return (numerator / gcd) + "/" + (denominator / gcd);
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return Math.max(a, 1);
    }
}
