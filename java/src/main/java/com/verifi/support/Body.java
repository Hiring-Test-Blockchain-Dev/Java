package com.verifi.support;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;

public final class Body {
    private Body() {}

    public static String text(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) return null;
        return String.valueOf(body.get(key)).trim();
    }

    public static Double decimal(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) return null;
        Object value = body.get(key);
        if (value instanceof Number number) return number.doubleValue();
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) return null;
        return Double.valueOf(raw);
    }

    public static Integer integer(Map<String, Object> body, String key) {
        Double value = decimal(body, key);
        return value == null ? null : value.intValue();
    }

    public static String str(ResultSet rs, String column) throws SQLException {
        return rs.getString(column);
    }

    public static Double dbl(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    public static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    public static boolean bool(ResultSet rs, String column) throws SQLException {
        return rs.getInt(column) != 0;
    }

    public static String walletKey(String address) {
        String trimmed = address.trim();
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("0x")) return trimmed.toLowerCase(Locale.ROOT);
        return trimmed;
    }
}
