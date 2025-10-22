package com.mouse.bet.model.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;
import java.util.Map;



@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserAgentProfile {
    private String id;
    private String type;
    private String userAgent;
    private Viewport viewport;
    private String platform;
    private Integer hardwareConcurrency;
    private Integer deviceMemory;
    private String webglVendor;
    private String webglRenderer;
    private Integer audioFrequency;
    private String canvasFillStyle;
    private String timeZone;
    private Geolocation geolocation;
    private List<String> fonts;
    private List<String> languages;
    private String colorEnum;
    private String cityCode;
    private ClientHints clientHints;
    private Webgl webgl;
    private List<Plugin> plugins;
    private Screen screen;
    private Connection connection;
    private Battery battery;
    private List<MediaDevice> mediaDevices;
    private Permissions permissions;
    private Storage storage;
    private Headers headers;

    // --- Nested Classes ---

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Viewport {
        private Integer width;
        private Integer height;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Geolocation {
        private Double latitude;
        private Double longitude;
        private Integer accuracy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClientHints {
        private List<Brand> brands;
        private String mobile;
        private String platform;

        @JsonProperty("platformVersion")
        private String platformVersion;

        private String architecture;
        private String bitness;
        private String model;
        private String uaFullVersion;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Brand {
            private String brand;
            private String version;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Webgl {
        private String vendor;
        private String renderer;
        private String version;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Plugin {
        private String name;
        private String filename;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Screen {
        private Integer availWidth;
        private Integer availHeight;
        private Integer colorDepth;
        private Integer pixelDepth;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Connection {
        private Double downlink;
        private String effectiveType;
        private Integer rtt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Battery {
        private Double level;
        private Boolean charging;
        private Integer chargingTime;
        private Integer dischargingTime;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MediaDevice {
        private String deviceId;
        private String kind;
        private String label;
        private String groupId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Permissions {
        private String notifications;
        private String geolocation;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Storage {
        private Long quota;
        private Long usage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Headers {
        private Map<String, String> standardHeaders;
        private Map<String, String> clientHintsHeaders;
    }
}
