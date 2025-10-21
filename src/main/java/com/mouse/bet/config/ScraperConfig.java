package com.mouse.bet.config;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
@Data
public class ScraperConfig {
    private final List<String> BROWSER_FlAGS= Arrays.asList(
            "--disable-blink-features=AutomationControlled",
            "--disable-infobars",
            "--disable-web-security",
            "--disable-http2",
            "--disable-extensions",
            "--disable-default-apps",
            "--disable-popup-blocking",
            "--disable-notifications",
            "--disable-translate",
            "--disable-gpu",
            "--no-sandbox",
            "--mute-audio",
            "--hide-scrollbars",
            "--disable-dev-shm-usage",
            "--disable-software-rasterizer",
            "--remote-debugging-port=0",
            "--no-first-run",
            "--no-service-autorun",
            "--no-default-browser-check",
            "--password-store=basic",
            "--single-process",
            "--no-zygote",
            "--use-mock-keychain",
            "--lang=en-US",
            "--window-size=1280,720",
            "--enable-quic",            // Enable alternative protocol
            "--quic-version=h3-29"
    );
}
