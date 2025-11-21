package com.mouse.bet.interfaces;

public interface BettingWindow {
    void run();
    void pause();
    void resume();
    void stop();
    boolean isRunning();
    boolean isPaused();
    void shutdown();
}
