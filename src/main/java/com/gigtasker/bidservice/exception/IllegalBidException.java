package com.gigtasker.bidservice.exception;

public class IllegalBidException extends RuntimeException {
    public IllegalBidException(String message) {
        super(message);
    }
}
