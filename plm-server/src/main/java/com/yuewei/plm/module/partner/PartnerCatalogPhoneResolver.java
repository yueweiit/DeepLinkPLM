package com.yuewei.plm.module.partner;

import com.yuewei.plm.module.moldreference.PhoneIdentityMatcher;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceService;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Read projection only: source model text reuses standard member identities, never allocates codes. */
@Service
@RequiredArgsConstructor
public class PartnerCatalogPhoneResolver {
    private final PhoneModelReferenceService references;
    private static final Pattern RANGE = Pattern.compile(
        "(?i)(?:(?:apple\\s*)?(?:iphone|ip)|苹果)\\s*([0-9]{1,2})\\s*(?:到|至|[-—–~～])\\s*(?:(?:iphone|ip|苹果)\\s*)?([0-9]{1,2})(?:\\s*(?:全?系列|series|全型号|所有型号))?");
    private static final Pattern APPLE_MODEL = Pattern.compile(
        "(?i)^(?:apple\\s*)?(?:iphone|ip|苹果)\\s*([0-9]{1,2})\\s*([a-z\\s]*)$");
    private static final Pattern BARE_APPLE_MODEL = Pattern.compile("(?i)^([0-9]{1,2})\\s*([a-z\\s]*)$");
    private static final Pattern APPLE_GENERATION = Pattern.compile("(?i)^iphone\\s*([0-9]{1,2})([a-z\\s]*)$");
    private static final Pattern MINI_EXCLUSION = Pattern.compile(
        "(?i)(?:mini\\s*(?:除外|不做|不供|排除)|(?:不含|不做|不供|排除|除外|except|excluding|without)\\s*mini)");
    private static final String APPLE_START = "(?:(?:apple\\s*)?(?:iphone|ip)|苹果)?\\s*[0-9]{1,2}";
    private static final String APPLE_END = "(?:\\s*(?:pro\\s*max|pro|max|plus|mini|air|e))?(?:\\s*(?:全?系列|series|全型号|所有型号))?";
    private static final String APPLE_OPERAND = APPLE_START
        + "(?:\\s*(?:到|至|[-—–~～])\\s*(?:(?:iphone|ip|苹果)\\s*)?[0-9]{1,2})?" + APPLE_END;
    private static final String EXCLUSION_LIST = APPLE_OPERAND + "(?:\\s*(?:[/、,，]|\\band\\b|和|及)\\s*" + APPLE_OPERAND + ")*";
    private static final String SIMPLE_EXCLUSION_LIST = APPLE_START + APPLE_END
        + "(?:\\s*(?:[/、,，]|\\band\\b|和|及)\\s*" + APPLE_START + APPLE_END + ")*";
    private static final Pattern NEGATIVE_PREFIX = Pattern.compile("(?i)(?:不含|不做|不供(?:应)?|不支持|停做|停供|排除|except|excluding|without)\\s*(" + EXCLUSION_LIST + ")");
    private static final Pattern NEGATIVE_SUFFIX = Pattern.compile("(?i)(?<![\\p{L}\\p{N}\\-—–~～至到])(" + SIMPLE_EXCLUSION_LIST + "|" + APPLE_OPERAND + ")\\s*(?:除外|不做|不供(?:应)?|不支持|停做|停供|排除|not\\s+available|not\\s+supported)");
    private static final Pattern NEGATIVE_AROUND = Pattern.compile("(?i)(?:除了|除)\\s*(" + EXCLUSION_LIST + ")\\s*以?外");
    private static final Pattern UNPARSED_DENIAL = Pattern.compile("(?i)不含|不做|不供|不支持|停做|停供|排除|除了?|以外|不能|不是|没有|except|excluding|without|not\\s+(?:available|supported)");
    private static final Pattern EXCLUSION_SEPARATOR = Pattern.compile("(?i)[/、,，]|\\band\\b|和|及");
    private static final Pattern SEPARATORS = Pattern.compile("[/\\\\;；|,，、\\n\\r]+");
    private static final Set<String> APPLE_SUFFIXES = Set.of("", "pro", "promax", "max", "plus", "mini", "air", "e");
    private static final List<String> PHONE_FIELDS = List.of("id", "phoneModelId", "brandId", "brandName",
        "modelName", "displayName", "codeItemId", "phoneModelCode", "status", "brandStatus", "codeStatus",
        "codeLinkStatus", "productionStatus");

    /** Only a genuinely absent supplier declaration may use the existing phone library.
     * A broken ID, unmatched text, negative-only scope or unexpanded range is not absence. */
    public static boolean supplierScopeProvided(Map<String,Object> row) {
        if (!text(row.get("modelText")).isBlank()) return true;
        for (String key : List.of("phoneModelIds", "availablePhoneModelIds", "unavailablePhoneModelIds", "phones", "availablePhones", "unavailablePhones", "pairings", "selectedPairs", "supplierModelEntries", "modelSheetRefs")) {
            Object value = row.get(key);
            if (value instanceof Collection<?> values ? !values.isEmpty() : !text(value).isBlank()) return true;
        }
        for (String key : List.of("rangeRefs", "rangeSnapshots")) {
            Object value = row.get(key);
            if (value instanceof Collection<?> values) {
                for (Object entry : values) {
                    if (!(entry instanceof Map<?,?> range) || !"color".equals(text(range.get("type")))) return true;
                }
            } else if (!text(value).isBlank()) return true;
        }
        return false;
    }

    /** Empty extraction is not proof that the supplier omitted a range (it may be in an image). */
    public static String supplierScopeMode(Map<String,Object> row) {
        if (supplierScopeProvided(row)) return "supplier";
        return "not_provided".equals(text(row.get("phoneScopeDeclaration"))) ? "unspecified" : "unresolved";
    }

    public List<Map<String, Object>> resolveAll(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        return newScope().resolveAll(rows);
    }

    /** Reuse one fresh snapshot across a paged/filter scan or a complete import; never a process-wide cache. */
    public ResolutionScope newScope() {
        return new ResolutionScope(references.matchingBrands(), references.matchingPhones());
    }

    public static final class ResolutionScope {
        private final List<Map<String, Object>> brands;
        private final Map<Long, Map<String, Object>> byBrand = new HashMap<>();
        private final Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        private final Map<Long, Map<String, Set<Long>>> exact = new HashMap<>();
        private final Set<Long> appleBrands = new HashSet<>();
        private final Map<Long, List<AppleComponent>> appleMembers = new HashMap<>();
        private final Map<SourceKey, AutomaticMatch> sourceMatches = new HashMap<>();
        private record SourceKey(String model, String brand) {}
        private record AutomaticMatch(List<Long> ids, List<String> issues) {}
        private record AppleComponent(int generation, String suffix) {}

        private ResolutionScope(List<Map<String, Object>> brands, List<Map<String, Object>> phones) {
            this.brands = brands == null ? List.of() : List.copyOf(brands);
            for (var brand : this.brands) {
                Long brandId = number(brand.get("brandId"));
                if (brandId != null) byBrand.put(brandId, brand);
                if (brandId != null && PhoneIdentityMatcher.brandNames(brand).stream()
                    .anyMatch(n -> Set.of("apple", "苹果").contains(n))) appleBrands.add(brandId);
            }
            for (var phone : phones == null ? List.<Map<String, Object>>of() : phones) {
                Long id = number(phone.get("phoneModelId"));
                if (id == null) continue;
                byId.put(id, new LinkedHashMap<>(phone));
                var brand = brand(number(phone.get("brandId")));
                if (brand != null && appleBrands.contains(number(brand.get("brandId"))))
                    appleMembers.put(id, appleComponents(phone, brand));
                if (brand == null || !enabled(phone, brand)) continue;
                var names = new ArrayList<String>();
                names.add(text(phone.get("modelName")));
                names.addAll(strings(phone.get("aliases")));
                names.addAll(strings(phone.get("matchingAliases")));
                for (String name : names) if (PhoneIdentityMatcher.isSingleModel(name)) {
                    String prepared = prepare(name, appleBrands.contains(number(brand.get("brandId"))));
                    String key = PhoneIdentityMatcher.identityKey(prepared, brand);
                    exact.computeIfAbsent(number(brand.get("brandId")), k -> new HashMap<>())
                        .computeIfAbsent(key, k -> new LinkedHashSet<>()).add(id);
                }
            }
        }

        public List<Map<String, Object>> resolveAll(List<Map<String, Object>> rows) {
            if (rows == null || rows.isEmpty()) return List.of();
            return rows.stream().map(this::resolve).toList();
        }

        public Map<String, Object> resolve(Map<String, Object> input) {
            var row = new LinkedHashMap<String, Object>(input == null ? Map.of() : input);
            row.put("phoneScopeMode", supplierScopeMode(row));
            String source = text(row.get("modelText"));
            var issues = new LinkedHashSet<String>();
            var ids = new LinkedHashSet<Long>();
            var rawIds = row.get("phoneModelIds");
            // Once engineering confirms a model table, even an empty result is explicit:
            // re-resolving an older free-text summary would resurrect a cancelled association.
            boolean tableDeclared = row.get("supplierModelEntries") instanceof Collection<?> entries && !entries.isEmpty();
            boolean explicit = rawIds instanceof Collection<?> values && !values.isEmpty() || tableDeclared;
            if (explicit) {
                for (Object raw : rawIds instanceof Collection<?> values ? values : List.of()) {
                    Long id = number(raw);
                    if (id == null) { issues.add("已关联手机型号身份无效，请核对原记录"); continue; }
                    // Retain missing identities as well: a read must not silently erase a broken historic link.
                    ids.add(id);
                    var phone = byId.get(id);
                    if (phone == null) issues.add("已关联手机型号不存在：" + id);
                    else if (!enabled(phone, brand(number(phone.get("brandId")))))
                        issues.add("已关联手机资料已停用，保留原关联：" + display(phone));
                }
            } else if (!source.isBlank()) {
                String sourceBrand = text(row.getOrDefault("brandName", row.get("brand")));
                var match = sourceMatches.computeIfAbsent(new SourceKey(source, sourceBrand), key -> {
                    var matched = new LinkedHashSet<Long>(); var unresolved = new LinkedHashSet<String>();
                    resolveSource(key.model(), key.brand(), matched, unresolved);
                    return new AutomaticMatch(List.copyOf(matched), List.copyOf(unresolved));
                });
                ids.addAll(match.ids()); issues.addAll(match.issues());
            }
            var resolved = ids.stream().map(byId::get).filter(Objects::nonNull).sorted(PHONE_ORDER).toList();
            var orderedIds = new ArrayList<Long>();
            resolved.forEach(p -> orderedIds.add(number(p.get("phoneModelId"))));
            ids.stream().filter(id -> !byId.containsKey(id)).sorted().forEach(orderedIds::add);
            var projected = resolved.stream().map(ResolutionScope::projection).toList();
            long groups = resolved.stream().map(ResolutionScope::groupIdentity).distinct().count();
            String state = issues.isEmpty() ? (resolved.isEmpty() ? "empty" : "matched") : resolved.isEmpty() ? "unmatched" : "partial";
            row.put("phoneModelIds", List.copyOf(orderedIds));
            row.put("phones", projected);
            row.put("phoneMatchIssues", List.copyOf(issues));
            row.put("phoneMatchSummary", Map.of("state", state, "matchedCount", resolved.size(),
                "codeGroupCount", groups, "unresolvedCount", issues.size(), "sourceText", source));
            return row;
        }

        private void resolveSource(String source, String sourceBrand, Set<Long> ids, Set<String> issues) {
            boolean excludeMini = MINI_EXCLUSION.matcher(source).find();
            String remaining = MINI_EXCLUSION.matcher(source).replaceAll("");
            boolean appleContext = RANGE.matcher(remaining).find()
                || Pattern.compile("(?i)(?:iphone|\\bip\\s*[0-9]|苹果)").matcher(remaining).find()
                || PhoneIdentityMatcher.sourceBrands(sourceBrand, "", brands).stream()
                    .anyMatch(b -> appleBrands.contains(number(b.get("brandId"))));
            var exclusions = new ArrayList<String>();
            if (appleContext) {
                remaining = extractExclusions(remaining, NEGATIVE_AROUND, exclusions);
                remaining = extractExclusions(remaining, NEGATIVE_PREFIX, exclusions);
                remaining = extractExclusions(remaining, NEGATIVE_SUFFIX, exclusions);
            }
            // A clear negative not covered by the grammar must never turn into positive range membership.
            boolean unexplainedDenial = UNPARSED_DENIAL.matcher(remaining).find();
            if (unexplainedDenial) {
                issues.add("原文含未明确解析的供货否定，未自动选入型号：" + source);
                return;
            }
            Matcher ranges = RANGE.matcher(remaining);
            StringBuffer afterRanges = new StringBuffer();
            while (ranges.find()) {
                int first = Integer.parseInt(ranges.group(1)), last = Integer.parseInt(ranges.group(2));
                if (first > last) issues.add("型号区间起止倒置：" + ranges.group());
                else {
                    Set<Integer> foundGenerations = new HashSet<>();
                    for (var phone : byId.values()) {
                        var brand = brand(number(phone.get("brandId")));
                        if (brand == null || !appleBrands.contains(number(brand.get("brandId"))) || !enabled(phone, brand)) continue;
                        var components = appleMembers.getOrDefault(number(phone.get("phoneModelId")), List.of());
                        for (var component : components) if (component.generation() >= first && component.generation() <= last
                            && (!excludeMini || !component.suffix().equals("mini"))) {
                            ids.add(number(phone.get("phoneModelId"))); foundGenerations.add(component.generation());
                        }
                    }
                    for (int n = first; n <= last; n++) if (!foundGenerations.contains(n))
                        issues.add("现有手机资料缺少 iPhone " + n + " 系列，未新建型号");
                }
                ranges.appendReplacement(afterRanges, " ");
            }
            ranges.appendTail(afterRanges);
            String tail = afterRanges.toString().replaceAll("[()（）\\[\\]【】]", " ").strip();
            Map<String, Object> lastBrand = null; String lastFamily = "";
            for (String part : SEPARATORS.split(tail)) {
                String token = part.strip();
                if (token.isEmpty()) continue;
                String prepared = prepare(token, appleContext);
                var found = new LinkedHashSet<Long>();
                List<Map<String, Object>> candidates = PhoneIdentityMatcher.sourceBrands(sourceBrand, prepared, brands);
                if (candidates.isEmpty() && appleContext && prepared.toLowerCase(Locale.ROOT).startsWith("iphone "))
                    candidates = brands.stream().filter(b -> appleBrands.contains(number(b.get("brandId"))) && "enabled".equals(b.get("status"))).toList();
                if (candidates.isEmpty() && lastBrand != null && !lastFamily.isEmpty()
                    && lastFamily.equals(family(prepared, lastBrand))) candidates = List.of(lastBrand);
                if (candidates.size() == 1) { lastBrand = candidates.get(0); lastFamily = family(prepared, lastBrand); }
                for (var brand : candidates) found.addAll(exact.getOrDefault(number(brand.get("brandId")), Map.of())
                    .getOrDefault(PhoneIdentityMatcher.identityKey(prepared, brand), Set.of()));
                if (excludeMini) found.removeIf(id -> isMini(byId.get(id)));
                if (found.size() == 1) ids.add(found.iterator().next());
                else if (found.isEmpty()) issues.add("未匹配现有手机型号：" + token);
                else issues.add("型号存在多个独立资料，请核对：" + token);
            }
            for (String exclusion : exclusions) excludeAppleMembers(exclusion, ids);
            if (ids.isEmpty() && !exclusions.isEmpty() && issues.isEmpty())
                issues.add("原文仅明确列出不供／排除型号，未作为正向适用型号");
        }

        private String extractExclusions(String source, Pattern pattern, List<String> exclusions) {
            Matcher matcher = pattern.matcher(source); var rest = new StringBuffer();
            while (matcher.find()) { exclusions.add(matcher.group(1)); matcher.appendReplacement(rest, " "); }
            matcher.appendTail(rest); return rest.toString();
        }

        private void excludeAppleMembers(String exclusion, Set<Long> ids) {
            for (String part : EXCLUSION_SEPARATOR.split(exclusion)) {
                String token = part.strip().replaceFirst("(?i)\\s*(?:全?系列|series|全型号|所有型号)$", "");
                String prefixed = token.matches("^[0-9].*") ? "iPhone " + token : token;
                Matcher range = RANGE.matcher(prefixed);
                if (range.matches()) {
                    int first = Integer.parseInt(range.group(1)), last = Integer.parseInt(range.group(2));
                    ids.removeIf(id -> appleMembers.getOrDefault(id, List.of()).stream().anyMatch(component ->
                        component.generation() >= Math.min(first, last) && component.generation() <= Math.max(first, last)));
                    continue;
                }
                Matcher model = APPLE_GENERATION.matcher(prepare(prefixed, true));
                if (!model.matches()) continue;
                int target = Integer.parseInt(model.group(1)); String suffix = model.group(2).replace(" ", "").toLowerCase(Locale.ROOT);
                ids.removeIf(id -> appleMembers.getOrDefault(id, List.of()).stream().anyMatch(component ->
                    component.generation() == target && (suffix.isEmpty() || suffix.equals(component.suffix()))));
            }
        }

        private String family(String model, Map<String, Object> brand) {
            Matcher leading = Pattern.compile("^[\\p{L}]{1,12}(?=[0-9])").matcher(PhoneIdentityMatcher.identityKey(model, brand));
            return leading.find() ? leading.group() : "";
        }

        /** A family projection may include an existing combined/hole-labelled member, never split its identity. */
        private List<AppleComponent> appleComponents(Map<String, Object> phone, Map<String, Object> brand) {
            var components = new LinkedHashSet<AppleComponent>();
            var names = new ArrayList<String>(); names.add(text(phone.get("modelName")));
            names.addAll(strings(phone.get("aliases")));
            for (String name : names) {
                String key = PhoneIdentityMatcher.modelKey(name, brand);
                String[] parts = SEPARATORS.split(key);
                // Apple also owns iPad/AirPods: a bare later number must not make those an iPhone.
                if (parts.length == 0 || !Pattern.compile("(?i)^(?:iphone|ip|苹果)\\s*[0-9]").matcher(parts[0].strip()).find()) continue;
                for (String part : parts) {
                    String core = part.strip().replaceFirst("(?i)\\s+(?:agujero\\s+(?:fino|grande)|gran\\s+agujero)$", "");
                    Matcher found = APPLE_GENERATION.matcher(prepare(core, true));
                    if (found.matches()) {
                        String suffix = found.group(2).replace(" ", "").toLowerCase(Locale.ROOT);
                        if (APPLE_SUFFIXES.contains(suffix)) components.add(new AppleComponent(Integer.parseInt(found.group(1)), suffix));
                    }
                }
            }
            return List.copyOf(components);
        }

        private boolean isMini(Map<String, Object> phone) {
            var components = appleMembers.getOrDefault(number(phone.get("phoneModelId")), List.of());
            return !components.isEmpty() && components.stream().allMatch(component -> component.suffix().equals("mini"));
        }

        private Map<String, Object> brand(Long id) {
            return byBrand.get(id);
        }
        private static boolean enabled(Map<String, Object> phone, Map<String, Object> brand) {
            return brand != null && "enabled".equals(brand.get("status")) && "enabled".equals(phone.get("status"))
                && !Set.of("disabled", "missing").contains(text(phone.get("codeLinkStatus")))
                && (!phone.containsKey("codeStatus") || phone.get("codeStatus") == null || "enabled".equals(phone.get("codeStatus")));
        }
        private static String display(Map<String, Object> phone) { return text(phone.getOrDefault("displayName", phone.get("modelName"))); }
        private static Object groupIdentity(Map<String, Object> phone) {
            if (phone.get("codeItemId") != null) return "id:" + phone.get("codeItemId");
            if (!text(phone.get("phoneModelCode")).isEmpty()) return "code:" + phone.get("phoneModelCode");
            return "unassigned:" + phone.get("phoneModelId");
        }
        private static Map<String, Object> projection(Map<String, Object> phone) {
            var result = new LinkedHashMap<String, Object>();
            for (String field : PHONE_FIELDS) if (phone.containsKey(field)) result.put(field, phone.get(field));
            result.putIfAbsent("id", phone.get("phoneModelId"));
            result.putIfAbsent("displayName", display(phone));
            return result;
        }
        private static final Comparator<Map<String, Object>> PHONE_ORDER = Comparator
            .comparing((Map<String, Object> p) -> text(p.get("phoneModelCode")).isEmpty() ? null : text(p.get("phoneModelCode")), Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(p -> text(p.get("modelName")))
            .thenComparing(p -> number(p.get("phoneModelId")));
    }

    private static String prepare(String text, boolean appleContext) {
        String normal = PhoneIdentityMatcher.normalize(text);
        Matcher apple = APPLE_MODEL.matcher(normal);
        if (!apple.matches() && appleContext) apple = BARE_APPLE_MODEL.matcher(normal);
        if (apple.matches()) {
            String suffix = apple.group(2).replace(" ", "");
            if (APPLE_SUFFIXES.contains(suffix)) return "iPhone " + apple.group(1) + (suffix.isEmpty() ? "" : " " + (suffix.equals("promax") ? "Pro Max" : suffix));
        }
        return normal;
    }
    private static Long number(Object value) {
        if (value == null) return null;
        try {
            String raw = value.toString();
            if (!raw.matches("[0-9]+")) return null;
            long result = Long.parseLong(raw); return result > 0 ? result : null;
        } catch (NumberFormatException ignored) { return null; }
    }
    private static String text(Object value) { return value instanceof String s ? s.strip() : ""; }
    private static List<String> strings(Object value) {
        return value instanceof Collection<?> values ? values.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of();
    }
}
