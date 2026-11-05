package com.yuewei.plm.common.util;

import java.util.Objects;

public final class ManufacturingHole {
    private ManufacturingHole() {}
    public static String canonicalHole(String value) {
        return switch(Objects.toString(value,"").strip()) {
            case "large","大孔","大孔Gran agujero" -> "large";
            case "precision","精孔","精孔Agujero fino" -> "precision";
            case "other","其他" -> "other";
            default -> "";
        };
    }
    public static String holeLabel(String value) {
        return switch(value) {case "large" -> "大孔";case "precision" -> "精孔";case "other" -> "其他";default -> "";};
    }
}
