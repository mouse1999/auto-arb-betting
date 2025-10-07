package com.mouse.bet.model.profile;

import lombok.Data;
import lombok.Getter;

import java.util.List;

@Data
@Getter
public class TeamAlias {
    private String name;
    private List<String> aliases;
}
