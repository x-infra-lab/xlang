package com.xlang.linker;

import com.xlang.vm.PageTable;
import com.xlang.vm.XMachine;
import com.xlang.vm.XOS;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A linked XE01 executable with page-separated text and data segments. */
public record XExecutable(int entryPoint, int dataAddress, byte[] text, byte[] data,
                          Map<String, Long> symbols) {
    public XExecutable {
        if (text.length == 0) throw new IllegalArgumentException("executable text is empty");
        if (entryPoint < 0 || entryPoint >= text.length) {
            throw new IllegalArgumentException("entry point outside text: " + entryPoint);
        }
        if (dataAddress < text.length || dataAddress % PageTable.PAGE_SIZE != 0) {
            throw new IllegalArgumentException("data address must be page aligned after text: " + dataAddress);
        }
        if ((long) dataAddress + data.length > XOS.HEAP_BASE) {
            throw new IllegalArgumentException("executable image overlaps heap base");
        }
        text = Arrays.copyOf(text, text.length);
        data = Arrays.copyOf(data, data.length);
        symbols = Collections.unmodifiableMap(new LinkedHashMap<>(symbols));
    }

    @Override public byte[] text() { return Arrays.copyOf(text, text.length); }
    @Override public byte[] data() { return Arrays.copyOf(data, data.length); }

    /** Returns the flat virtual image, including the page-alignment gap. */
    public byte[] image() {
        if (data.length == 0) return text();
        byte[] image = new byte[dataAddress + data.length];
        System.arraycopy(text, 0, image, 0, text.length);
        System.arraycopy(data, 0, image, dataAddress, data.length);
        return image;
    }

    /** Loads text as r-x and data as rw- into an XMachine process. */
    public void loadInto(XMachine machine) {
        machine.load(text, dataAddress, data);
    }
}
