package com.xlang.compiler.object;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Stable binary serializer for the XO01 relocatable object format. */
public final class XObjectIO {
    private static final int MAGIC = 0x584f3031; // XO01
    private static final int MAX_COUNT = 1_000_000;
    private XObjectIO() {}

    public static void write(Path path, XObject object) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            out.writeInt(MAGIC);
            byte[] text = object.text(), data = object.data();
            out.writeInt(text.length); out.writeInt(data.length);
            out.writeInt(object.symbols().size()); out.writeInt(object.relocations().size());
            out.write(text); out.write(data);
            for (XObject.Symbol symbol : object.symbols()) {
                writeString(out, symbol.name()); out.writeByte(symbol.section().ordinal());
                out.writeInt(symbol.offset()); out.writeInt(symbol.size()); out.writeBoolean(symbol.global());
            }
            for (XObject.Relocation relocation : object.relocations()) {
                out.writeByte(relocation.section().ordinal()); out.writeInt(relocation.offset());
                out.writeByte(relocation.type().ordinal()); writeString(out, relocation.symbol());
                out.writeLong(relocation.addend());
            }
        }
    }

    public static XObject read(Path path) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (in.readInt() != MAGIC) throw new IOException("not an XO01 object: " + path);
            int textLength = checkedCount(in.readInt(), "text length");
            int dataLength = checkedCount(in.readInt(), "data length");
            int symbolCount = checkedCount(in.readInt(), "symbol count");
            int relocationCount = checkedCount(in.readInt(), "relocation count");
            byte[] text = in.readNBytes(textLength), data = in.readNBytes(dataLength);
            if (text.length != textLength || data.length != dataLength) throw new IOException("truncated XO01 sections");
            List<XObject.Symbol> symbols = new ArrayList<>();
            for (int index = 0; index < symbolCount; index++) {
                String name = readString(in); XObject.Section section = section(in.readUnsignedByte());
                symbols.add(new XObject.Symbol(name, section, in.readInt(), in.readInt(), in.readBoolean()));
            }
            List<XObject.Relocation> relocations = new ArrayList<>();
            for (int index = 0; index < relocationCount; index++) {
                XObject.Section section = section(in.readUnsignedByte()); int offset = in.readInt();
                int type = in.readUnsignedByte();
                if (type >= XObject.RelocationType.values().length) throw new IOException("invalid relocation type " + type);
                relocations.add(new XObject.Relocation(section, offset, XObject.RelocationType.values()[type],
                    readString(in), in.readLong()));
            }
            if (in.read() != -1) throw new IOException("trailing bytes after XO01 object");
            return new XObject(text, data, symbols, relocations);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
    private static String readString(DataInputStream in) throws IOException {
        int length = checkedCount(in.readInt(), "string length"); byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new IOException("truncated XO01 string");
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static int checkedCount(int value, String label) throws IOException {
        if (value < 0 || value > MAX_COUNT) throw new IOException("invalid " + label + ": " + value); return value;
    }
    private static XObject.Section section(int ordinal) throws IOException {
        if (ordinal >= XObject.Section.values().length) throw new IOException("invalid section " + ordinal);
        return XObject.Section.values()[ordinal];
    }
}
