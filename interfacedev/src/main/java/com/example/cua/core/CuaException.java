package com.example.cua.core;

/** Base unchecked exception for unrecoverable conditions in the CUA system. */
public class CuaException extends RuntimeException {
    public CuaException(String message) {
        super(message);
    }

    public CuaException(String message, Throwable cause) {
        super(message, cause);
    }
}
