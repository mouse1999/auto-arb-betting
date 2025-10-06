package com.mouse.bet.model.profile;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mouse.bet.enums.Color;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Data
public class UserAgentProfile {
    private String id;
    private String type; //desktop, mobile etc
    @JsonProperty("SecChUaPlatform")
    private String secChUaPlatform;
    private String userAgent;
    private ViewPort viewport;
    private String platform;
    private String browser;
    private String secChUa;
    private String secChUaMobile;
    private List<String> fonts;

    private  boolean webdriver;
    private  String[] languages;
    private  int[] plugins;
    private Color colorEnum;
    private  int hardwareConcurrency;
    private int deviceMemory;
    private double audioFrequency;
    private String canvasFillStyle;
    private String webglVendor;
    private String webglRenderer;
    private String notificationsPermission;
    private GeoLocation geolocation;
    private String timeZone;
    private String cityCode;
}
