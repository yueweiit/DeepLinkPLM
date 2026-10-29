package com.yuewei.plm.module.moldapplication;

import java.util.*;
import static com.yuewei.plm.module.moldapplication.MoldApplicationForms.text;

/** Approval readiness is distinct from the identities required to make and register a mold. */
public record MoldApplicationSubmissionPolicy(String mode, boolean requiresCodes, boolean requiresStandardPhone,
        boolean requiresTipoMapping, List<String> allowedTipos, List<String> filingMissing) {
    static boolean researchProduct(Map<String,Object> product) {
        return product != null && "product_line".equals(product.get("product_type"))
            && "launch_v1".equals(product.get("development_mode"));
    }

    static MoldApplicationSubmissionPolicy of(Long productId, Map<String,Object> product, boolean trustedResearchSource,
            Long phoneId, Long phoneCodeId, String generatedCodes, String tipo,
            List<String> officialTipos, List<Map<String,Object>> configs) {
        boolean research = trustedResearchSource && researchProduct(product);
        var allowed = new ArrayList<String>();
        for (String option : officialTipos) {
            var mapped = configs.stream().filter(c -> Objects.equals(option, c.get("tipo"))).toList();
            if (research ? mapped.isEmpty() || mapped.stream().allMatch(c -> Objects.equals(productId,c.get("productLineId")))
                : mapped.stream().anyMatch(c -> Objects.equals(productId,c.get("productLineId")))) allowed.add(option);
        }
        var missing = new ArrayList<String>();
        if (research) {
            if (text(product.get("product_specific_code")) == null) missing.add("产品特定编码");
            if (phoneId == null) missing.add("标准手机型号");
            if (phoneCodeId == null) missing.add("手机型号编码");
            if (!MoldApplicationForms.hasValidCodes(generatedCodes)) missing.add("正式模具编码");
            if (configs.stream().noneMatch(c -> Objects.equals(tipo,c.get("tipo")) && Objects.equals(productId,c.get("productLineId"))))
                missing.add("Tipo与产品及材质编码映射");
        }
        return new MoldApplicationSubmissionPolicy(research ? "research" : "model", !research, !research, !research,
            List.copyOf(new LinkedHashSet<>(allowed)), List.copyOf(missing));
    }
}
