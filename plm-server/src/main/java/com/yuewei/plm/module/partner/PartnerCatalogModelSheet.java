package com.yuewei.plm.module.partner;

import java.util.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Supplier model-table evidence belongs to one source style, never to the phone master or a stock balance. */
final class PartnerCatalogModelSheet {
    private PartnerCatalogModelSheet() {}
    private static final List<String> RAW_FIELDS = List.of("rawBrandGroup", "rawModelText", "rawStatus", "rawNote", "matchText", "matchNote");
    private static final Set<String> INPUT_FIELDS = Set.of("supplierIndex", "rawBrandGroup", "rawModelText", "rawStatus", "rawNote", "matchText", "matchNote", "phoneModelIds");

    static List<Map<String,Object>> resolve(Object input, PartnerCatalogPhoneResolver.ResolutionScope scope) {
        require(input instanceof List<?> && !((List<?>)input).isEmpty(), "请填写本款型号表，不能用空白清除既有依据");
        var result = new ArrayList<Map<String,Object>>();
        var seen = new HashSet<Long>();
        for (Object raw : (List<?>)input) {
            require(raw instanceof Map<?,?>, "型号表每条必须是明确的记录");
            var entry = map(raw);
            require(INPUT_FIELDS.containsAll(entry.keySet()), "型号表包含非型号字段，请保留原行报价等资料");
            long index = number(entry.get("supplierIndex"));
            require(index > 0 && seen.add(index), "供方序号须为不重复的正整数");
            var clean = new LinkedHashMap<String,Object>();
            clean.put("supplierIndex", index);
            for (String field : RAW_FIELDS) {
                String value = Objects.toString(entry.get(field), "");
                require(value.length() <= 12000, "型号表单项文字过长：" + field);
                clean.put(field, value);
            }
            required(clean.get("rawModelText"), "供方原型号");
            String original = text(clean.get("rawModelText")), match = text(clean.get("matchText"));
            if (!match.isEmpty() && !match.equals(original)) required(clean.get("matchNote"), "匹配用名称的核对依据");
            // A normalized name is an explicit, explained input. It never changes the supplier original.
            var candidate = new LinkedHashMap<String,Object>();
            candidate.put("modelText", match.isEmpty() ? original : match);
            if (match.isEmpty()) candidate.put("brandName", text(clean.get("rawBrandGroup")));
            var resolved = scope.resolve(candidate);
            var ids = PartnerSelectionService.scopeIds(resolved.get("phoneModelIds"));
            clean.put("automaticPhoneModelIds", ids);
            clean.put("automaticIssues", resolved.getOrDefault("phoneMatchIssues", List.of()));
            boolean manual = false;
            if (entry.containsKey("phoneModelIds")) {
                require(entry.get("phoneModelIds") instanceof List<?>, "手机型号身份须为列表");
                var chosen = PartnerSelectionService.scopeIds(entry.get("phoneModelIds"));
                if (!new HashSet<>(chosen).equals(new HashSet<>(ids))) {
                    required(clean.get("matchNote"), "人工关联现有手机型号的核对依据");
                    manual = true;
                    resolved = chosen.isEmpty() ? Map.of("phoneModelIds", List.of(), "phones", List.of(), "phoneMatchIssues", List.of("工程已保留原文暂不关联型号")) : scope.resolve(Map.of("phoneModelIds", chosen));
                    require(chosen.isEmpty() || maps(resolved.get("phones")).size() == chosen.size()
                        && ((List<?>)resolved.getOrDefault("phoneMatchIssues", List.of())).isEmpty(), "人工关联只能使用现有且启用的品牌、手机成员和编码；不能创建或猜测身份");
                    ids = chosen;
                }
            }
            clean.put("phoneModelIds", ids);
            clean.put("phones", maps(resolved.get("phones")));
            var issues = new LinkedHashSet<String>();
            if (manual) for (Object issue : (List<?>)clean.get("automaticIssues")) issues.add(text(issue));
            for (Object issue : (List<?>)resolved.getOrDefault("phoneMatchIssues", List.of())) issues.add(text(issue));
            clean.put("issues", List.copyOf(issues));
            clean.put("matchMethod", manual ? "manual" : "exact");
            clean.put("matchState", manual ? (ids.isEmpty() ? "unmatched" : "manual") : text(map(resolved.get("phoneMatchSummary")).get("state")));
            result.add(clean);
        }
        return result;
    }

    /** A read refreshes display/status for frozen IDs, but never rematches unresolved text against a newer dictionary. */
    static List<Map<String,Object>> project(Object input, PartnerCatalogPhoneResolver.ResolutionScope scope) {
        var result = new ArrayList<Map<String,Object>>();
        for (var entry : maps(input)) {
            var out = new LinkedHashMap<String,Object>();
            out.put("supplierIndex", entry.get("supplierIndex"));
            for (String field : RAW_FIELDS) out.put(field, entry.getOrDefault(field, ""));
            for (String field : List.of("automaticPhoneModelIds", "automaticIssues", "matchMethod")) if (entry.containsKey(field)) out.put(field, entry.get(field));
            var ids = PartnerSelectionService.scopeIds(entry.get("phoneModelIds"));
            out.put("phoneModelIds", ids);
            if (ids.isEmpty()) {
                out.put("phones", List.of());
                out.put("issues", entry.getOrDefault("issues", List.of()));
                out.put("matchState", entry.getOrDefault("matchState", "unmatched"));
            } else {
                var resolved = scope.resolve(Map.of("phoneModelIds", ids));
                out.put("phones", maps(resolved.get("phones")));
                var issues = new LinkedHashSet<String>();
                for (Object value : (List<?>)entry.getOrDefault("issues", List.of())) issues.add(text(value));
                for (Object value : (List<?>)resolved.getOrDefault("phoneMatchIssues", List.of())) issues.add(text(value));
                out.put("issues", List.copyOf(issues));
                out.put("matchState", "manual".equals(text(entry.get("matchMethod"))) && ((List<?>)resolved.getOrDefault("phoneMatchIssues", List.of())).isEmpty()
                    ? "manual" : issues.isEmpty() ? "matched" : "partial");
            }
            result.add(out);
        }
        return result;
    }

    static Map<String,Object> summary(List<Map<String,Object>> entries) {
        long matched = entries.stream().filter(e -> "matched".equals(text(e.get("matchState")))).count();
        long manual = entries.stream().filter(e -> "manual".equals(text(e.get("matchState")))).count();
        long automaticIssues = entries.stream().filter(e -> !((List<?>)e.getOrDefault("automaticIssues", List.of())).isEmpty()).count();
        var ids = new LinkedHashSet<Long>();
        for (var entry : entries) ids.addAll(PartnerSelectionService.scopeIds(entry.get("phoneModelIds")));
        return Map.of("entryCount", entries.size(), "matchedEntryCount", matched,
            "manualEntryCount", manual, "automaticIssueEntryCount", automaticIssues,
            "unresolvedEntryCount", entries.size() - matched - manual, "phoneCount", ids.size());
    }
}
