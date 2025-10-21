package com.mouse.bet.exception;

public class InvalidArbException extends RuntimeException{

    public InvalidArbException(){
        super();
    }
    public InvalidArbException(String message) {
        super(message);
    }
}
