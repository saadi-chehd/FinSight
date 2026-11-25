package com.finsight.api;

public class DownstreamServiceException extends RuntimeException {
    private final int status;
    private final String error;

    public DownstreamServiceException(int status, String error, String message) {
        super(message);
        this.status = status;
        this.error = error;
    }

    public DownstreamServiceException(int status, String error, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.error = error;
    }

    public int status() {
        return status;
    }

    public String error() {
        return error;
    }
}
