package com.mouse.bet.exception;

public class NavigationException extends RuntimeException{
    public NavigationException() {
        super();
    }

    public NavigationException(String message) {
        super(message);
    }

    public NavigationException(String message, Throwable e) {
        super(message, e);
    }
}
