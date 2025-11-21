package com.mouse.bet.exception;

public class LoginException extends RuntimeException{
    public LoginException() {
        super();
    }

    public LoginException(String message) {
        super(message);
    }
    public LoginException(String message, Throwable e) {
        super(message, e);
    }

}
