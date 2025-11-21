package com.mouse.bet.exception;

public class CaptchaDetectedException extends RuntimeException{
    public CaptchaDetectedException(String message) {
        super(message);

    }

    public  CaptchaDetectedException() {
        super();
    }
}
