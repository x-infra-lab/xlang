package com.xlang.vm;

import java.util.EnumSet;

/** Page protection flags and their familiar rwx rendering. */
public enum Protection {
    READ('r', Access.READ), WRITE('w', Access.WRITE), EXECUTE('x', Access.EXECUTE);

    private final char symbol;
    private final Access access;
    Protection(char symbol, Access access) { this.symbol = symbol; this.access = access; }

    public static EnumSet<Protection> parse(String text) {
        EnumSet<Protection> result = EnumSet.noneOf(Protection.class);
        String normalized = text.toLowerCase();
        for (char character : normalized.toCharArray()) {
            switch (character) {
                case 'r' -> result.add(READ); case 'w' -> result.add(WRITE);
                case 'x' -> result.add(EXECUTE); case '-' -> { }
                default -> throw new IllegalArgumentException("invalid protection character '" + character + "'");
            }
        }
        return result;
    }

    static boolean allows(EnumSet<Protection> protections, Access requested) {
        return protections.stream().anyMatch(protection -> protection.access == requested);
    }

    public static String format(EnumSet<Protection> protections) {
        StringBuilder result = new StringBuilder(3);
        for (Protection protection : values()) result.append(protections.contains(protection) ? protection.symbol : '-');
        return result.toString();
    }
}
