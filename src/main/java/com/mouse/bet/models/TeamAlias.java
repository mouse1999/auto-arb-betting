package com.mouse.bet.models;

import lombok.Data;
import lombok.Getter;

import java.util.List;

@Data
@Getter
public class TeamAlias {
    private String name;
    private List<String> aliases;
}
