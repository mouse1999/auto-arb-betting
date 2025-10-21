package com.mouse.bet.model.arb;

public record LegResult(boolean success, String message) {
    public static LegResult ok() {
        return new LegResult(true,  "OK");
    }
    public static LegResult failed(String msg){ return new LegResult(false, msg); }
}