package com.mouse.bet.model.msport;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MsMarket {
    private String betAssistWidget;
    private String description;
    private int expand;
    private String group;
    // Note: 'guidance' is null
    private Object guidance;
    private int id;
    private int isFavourite;
    private int isShown;
    // Note: 'marketLabels' is null
    private Object marketLabels;
    private String name;
    private List<MsOutcome> outcomes;
    private int presentationType;
    private int priority;
    private int product;
    // Note: 'relatedMarketId' is null
    private Object relatedMarketId;
    // Note: 'relatedSpecifiers' is null
    private Object relatedSpecifiers;
    private String specifiers;
    private int status;
    private String title;
    private int userFavourite;

}
