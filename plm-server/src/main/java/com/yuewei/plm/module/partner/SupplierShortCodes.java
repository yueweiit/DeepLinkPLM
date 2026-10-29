package com.yuewei.plm.module.partner;

import java.util.Locale;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** One rule for the supplier metadata write path; historic identifiers are never cleaned in place. */
final class SupplierShortCodes {
    private SupplierShortCodes() {}

    static String change(Object submitted, String previous) {
        String value=text(submitted),old=previous==null?"":previous;
        // Saving an unchanged legacy code must not rewrite it or block unrelated metadata maintenance.
        if(value.equalsIgnoreCase(old.trim()))return old;
        String normalized=value.toUpperCase(Locale.ROOT);
        require(!old.trim().equalsIgnoreCase("XD")||normalized.equals("XD"),"XD为已确认的供应商简码，不能清空或改给其他供应商");
        require(normalized.isEmpty()||normalized.matches("[A-Z0-9][A-Z0-9-]*"),"供应商简码须以英文字母或数字开头，仅含英文字母、数字和半角横线，不含中文或下划线");
        return normalized;
    }
}
