package com.mouse.bet.model.msport;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MsOutcome {
    private String description;
    private String id;
    private int isActive;
    private String odds;
    private String probability;
}
