package com.mouse.bet.model.profile;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Data
public class GeoLocation {
    private double latitude;
    private double longitude;
    private Double accuracy;
}
