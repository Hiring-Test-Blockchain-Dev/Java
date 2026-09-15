package com.verifi.error;

public class ApiException extends RuntimeException {
    private final int status;
    private final String code;

    public ApiException(int status, String message, String code) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
