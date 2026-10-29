package com.yuewei.plm.module.productionmetadata;

import com.yuewei.plm.common.exception.BusinessException;
import java.util.Locale;
import java.util.Set;

public final class ProductionStatus {
    private ProductionStatus() {}
    public static final String UNKNOWN="unknown";
    public static String display(String value) { return value==null ? UNKNOWN : normalize(value); }
    public static String normalize(String value) {
        String result=value==null?"":value.strip().toLowerCase(Locale.ROOT);
        if(!Set.of(UNKNOWN,"active","discontinued").contains(result))
            throw new BusinessException(40001,"生产状态必须为待确认、在用或已停产");
        return result;
    }
}
