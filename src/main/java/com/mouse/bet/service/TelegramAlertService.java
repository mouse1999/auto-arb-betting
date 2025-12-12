package com.mouse.bet.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@ConditionalOnProperty(name = "monitoring.alerts.telegram.enabled", havingValue = "true")
public class TelegramAlertService {

    @Value("${monitoring.alerts.telegram.bot-token}")
    private String botToken;

    @Value("${monitoring.alerts.telegram.chat-id}")
    private String chatId;

    public void sendRollbackFailureAlert(String arbId, String betId, String bookmaker) {
        String message = String.format(
                "🚨 ROLLBACK FAILED 🚨\n\n" +
                        "ArbId: %s\n" +
                        "BetId: %s\n" +
                        "Bookmaker: %s\n\n" +
                        "⚠️ MANUAL INTERVENTION REQUIRED",
                arbId, betId, bookmaker
        );

        sendTelegramMessage(message);
    }

    public void sendHighRollbackRateAlert(double rollbackRate, int count) {
        String message = String.format(
                "⚠️ HIGH ROLLBACK RATE DETECTED\n\n" +
                        "Rate: %.2f%%\n" +
                        "Count: %d\n\n" +
                        "Please review betting strategy",
                rollbackRate, count
        );

        sendTelegramMessage(message);
    }

    private void sendTelegramMessage(String message) {
        try {
            // Implementation using Telegram Bot API
            // You can use libraries like telegram-bot-api or just HTTP client
            log.info("Sending Telegram alert: {}", message);
        } catch (Exception e) {
            log.error("Failed to send Telegram alert", e);
        }
    }
}
