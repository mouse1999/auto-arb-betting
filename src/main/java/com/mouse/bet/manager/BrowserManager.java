package com.mouse.bet.manager;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.mouse.bet.model.profile.UserAgentProfile;
import com.mouse.bet.model.profile.ViewPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Slf4j
public class BrowserManager {

    public Browser.NewContextOptions createStealthContextOptions(UserAgentProfile profile, Map<String, String> headers) {
        log.debug("Creating stealth context options");
        ViewPort viewPort = profile.getViewport();

        return new Browser.NewContextOptions()
                .setUserAgent(profile.getUserAgent())
                .setViewportSize(viewPort.getWidth(), viewPort.getHeight())
                .setLocale("en-US")
                .setTimezoneId("Europe/Amsterdam") //
//                .setGeolocation(profile.) // i will have this config set up in config file
//                .setJavaScriptEnabled(false)
//                .setIgnoreHTTPSErrors(true)
                .setExtraHTTPHeaders(headers)
                .setIgnoreHTTPSErrors(true)  // Ignore SSL/TLS errors
                .setServiceWorkers(ServiceWorkerPolicy.BLOCK);

    }
}
