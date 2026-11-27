package com.yuewei.plm.module.integration.crm.productlisting;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static com.yuewei.plm.module.partner.PartnerValues.*;

final class CrmListingDigest {
    private CrmListingDigest() {}
    static String of(ObjectMapper json,Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(signature(json,value).getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw invalid("CRM 推送摘要生成失败"); }
    }
}
