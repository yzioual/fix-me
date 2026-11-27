package com.fortytwo.fixme.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class FIXMessage {
    private FIXMessage() {}

    public static ByteBuffer addHeader(byte[] message) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(Integer.BYTES + message.length);
        byteBuffer.putInt(message.length);
        byteBuffer.put(message);
        byteBuffer.flip();
        return byteBuffer;
    }

    public static String checksum(String body) {
        int sum = 0;
        for (byte b : body.getBytes(StandardCharsets.US_ASCII)) {
            sum += b & 0xFF;
        }
        return String.format("%03d", sum % 256);
    }

    public static String build(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        fields.forEach((key, value) -> sb.append(key).append('=').append(value).append('|'));
        String body = sb.toString();
        return body + "10=" + checksum(body) + "|";
    }

    public static Map<String, String> parse(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        String[] pairs = raw.split("\\|");

        for (String pair : pairs) {
            String[] els = pair.split("=", 2);
            if (els.length != 2 || els[0].isEmpty() || els[1].isEmpty()) continue;
            result.put(els[0], els[1]);
        }
        return result;
    }

    public static boolean isChecksumValid(String raw) {
        int indexOfChecksum = raw.lastIndexOf("|10=");
        if (indexOfChecksum == -1 || !raw.endsWith("|")) return false;
        String body = raw.substring(0, indexOfChecksum + 1);
        String value = raw.substring(indexOfChecksum + 4, raw.lastIndexOf("|"));
        if (!value.matches("\\d{3}")) {
            return false;
        }
        return checksum(body).equals(value);
    }
}
