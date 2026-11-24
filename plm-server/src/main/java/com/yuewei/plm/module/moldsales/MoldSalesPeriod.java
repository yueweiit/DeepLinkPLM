package com.yuewei.plm.module.moldsales;

import com.yuewei.plm.common.exception.BusinessException;
import java.time.LocalDate;
import java.util.List;

/** The CRM and ecount sources use the same order business date. */
record MoldSalesPeriod(LocalDate dateFrom, LocalDate dateTo) {
    static final MoldSalesPeriod ALL_TIME = new MoldSalesPeriod(null, null);

    MoldSalesPeriod {
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new BusinessException(40001, "开始日期不能晚于结束日期");
        }
    }

    boolean bounded() {
        return dateFrom != null || dateTo != null;
    }

    String sql(String column, List<Object> arguments) {
        if (dateFrom != null) arguments.add(dateFrom);
        if (dateTo != null) arguments.add(dateTo);
        if (dateFrom == null) return dateTo == null ? "true" : column + " <= ?";
        if (dateTo == null) return column + " >= ?";
        return column + " between ? and ?";
    }
}
