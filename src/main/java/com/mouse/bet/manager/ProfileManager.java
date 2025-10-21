package com.mouse.bet.manager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mouse.bet.exception.DeviceNotFoundException;
import com.mouse.bet.model.profile.UserAgentProfile;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class ProfileManager {
    private List<UserAgentProfile> profiles = new ArrayList<>();;
    private final AtomicInteger currentIndex = new AtomicInteger(0);
    @PostConstruct
    void init() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream("static/devices.json");

            if (inputStream == null) {
                throw new IllegalStateException("devices.json not found in classpath.");
            }

            List<UserAgentProfile> deviceList = mapper.readValue(inputStream, new TypeReference<List<UserAgentProfile>>() {});
            profiles.addAll(deviceList);

            log.info("Loaded {} devices successfully", deviceList.size());

        } catch (Exception e) {
            log.error("Failed to load devices from JSON", e);
            throw new RuntimeException("Device initialization failed", e);
        }
    }
    public UserAgentProfile getProfile(String deviceId) {
        return profiles.stream()
                .filter(p -> p.getId().equals(deviceId))
                .findFirst()
                .orElseThrow(()-> new DeviceNotFoundException("Device is not found by this ID"));
    }

    public UserAgentProfile getNextProfile() {
        if (profiles == null || profiles.isEmpty()) {
            throw new DeviceNotFoundException("No device found at this time");
        }

        int index = currentIndex.getAndUpdate(i -> (i + 1) % profiles.size());
        UserAgentProfile agentProfile = profiles.get(index);

        log.info("Selected profile index: {}, ID: {}, User-Agent: {}",
                index,
                agentProfile.getId(),
                agentProfile.getUserAgent()
        );

        return agentProfile;
    }


}

