package com.mouse.bet.enums;

import com.fasterxml.jackson.annotation.JsonFormat;

@JsonFormat(shape = JsonFormat.Shape.OBJECT)
public enum Color {
    LIGHT,
    DARK
}