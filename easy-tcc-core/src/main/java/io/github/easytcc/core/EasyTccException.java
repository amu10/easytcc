package io.github.easytcc.core;

public class EasyTccException extends RuntimeException {
    public EasyTccException(String message) { super(message); }
    public EasyTccException(String message, Throwable cause) { super(message, cause); }
}
