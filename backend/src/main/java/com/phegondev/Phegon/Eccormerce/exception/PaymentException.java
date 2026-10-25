package com.phegondev.Phegon.Eccormerce.exception;

import org.springframework.http.HttpStatus;

/** Business/payment error carrying the HTTP status the API should answer with. */
public class PaymentException extends RuntimeException {

    private final HttpStatus status;

    public PaymentException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public PaymentException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
