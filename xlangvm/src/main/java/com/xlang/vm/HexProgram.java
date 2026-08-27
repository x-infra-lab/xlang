package com.xlang.vm;

/** Parser and formatter for CLI-friendly hexadecimal machine programs. */
public final class HexProgram {
    private HexProgram() {}

    public static byte[] parse(String text) {
        String compact = text.replaceAll("(?i)0x", "").replaceAll("[\\s_]", "");
        if (compact.isEmpty()) throw new IllegalArgumentException("hex program is empty");
        if ((compact.length() & 1) != 0) throw new IllegalArgumentException("hex program must contain whole bytes");
        byte[] bytes = new byte[compact.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(compact.charAt(i * 2), 16);
            int low = Character.digit(compact.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("invalid hex digit at character " + (i * 2));
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }

    public static String format(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) result.append(' ');
            result.append(String.format("%02x", bytes[i] & 0xff));
        }
        return result.toString();
    }
}
