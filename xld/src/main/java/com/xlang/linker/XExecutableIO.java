package com.xlang.linker;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stable binary serializer for the XE01 executable format. */
public final class XExecutableIO {
    private static final int MAGIC = 0x58453031; // XE01
    private static final int MAX_COUNT = 1_000_000;

    private XExecutableIO() {}

    public static void write(Path path, XExecutable executable) throws IOException {
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(path)))) {
            byte[] text = executable.text();
            byte[] data = executable.data();
            out.writeInt(MAGIC);
            out.writeInt(executable.entryPoint());
            out.writeInt(executable.dataAddress());
            out.writeInt(text.length);
            out.writeInt(data.length);
            out.writeInt(executable.symbols().size());
            out.write(text);
            out.write(data);
            for (Map.Entry<String, Long> symbol : executable.symbols().entrySet()) {
                writeString(out, symbol.getKey());
                out.writeLong(symbol.getValue());
            }
        }
    }

    public static XExecutable read(Path path) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path)))) {
            if (in.readInt() != MAGIC) throw new IOException("not an XE01 executable: " + path);
            int entryPoint = in.readInt();
            int dataAddress = in.readInt();
            int textLength = checkedCount(in.readInt(), "text length");
            int dataLength = checkedCount(in.readInt(), "data length");
            int symbolCount = checkedCount(in.readInt(), "symbol count");
            byte[] text = in.readNBytes(textLength);
            byte[] data = in.readNBytes(dataLength);
            if (text.length != textLength || data.length != dataLength) {
                throw new IOException("truncated XE01 sections");
            }
            Map<String, Long> symbols = new LinkedHashMap<>();
            for (int index = 0; index < symbolCount; index++) {
                String name = readString(in);
                if (symbols.putIfAbsent(name, in.readLong()) != null) {
                    throw new IOException("duplicate XE01 symbol '" + name + "'");
                }
            }
            if (in.read() != -1) throw new IOException("trailing bytes after XE01 executable");
            try {
                return new XExecutable(entryPoint, dataAddress, text, data, symbols);
            } catch (IllegalArgumentException exception) {
                throw new IOException("invalid XE01 executable: " + exception.getMessage(), exception);
            }
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = checkedCount(in.readInt(), "string length");
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new IOException("truncated XE01 string");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int checkedCount(int value, String label) throws IOException {
        if (value < 0 || value > MAX_COUNT) throw new IOException("invalid " + label + ": " + value);
        return value;
    }
}
