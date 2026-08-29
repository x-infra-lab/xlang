package com.xlang.linker;

/** A deterministic, user-facing failure while resolving or relocating objects. */
public final class LinkException extends Exception {
    public LinkException(String message) { super(message); }
}
