package com.yuewei.plm.common.util;

import java.util.Objects;

public final class ManufacturingHole {
    private ManufacturingHole() {}
    public static String canonicalHole(String value) {
        return switch(Objects.toString(value,"").strip()) {
            case "large","大孔","大孔Gran agujero" -> "large";
            case "precision","精孔","精孔Agujero fino" -> "precision";
            case "mixed","混合","混合Mixto" -> "mixed";
            case "other","其他" -> "other";
            default -> "";
        };
    }
    public static String holeLabel(String value) {
        return switch(value) {case "large" -> "大孔";case "precision" -> "精孔";case "mixed" -> "混合";case "other" -> "其他";default -> "";};
    }
    public static String moldMarker(String value) {
        return switch(canonicalHole(value)) {case "large" -> "D";case "precision" -> "J";case "mixed" -> "H";default -> "";};
    }
}
