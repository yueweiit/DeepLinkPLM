package com.yuewei.plm.module.moldreference;

import java.util.*;
import java.util.regex.Pattern;

/** Exact identity keys only. Crawl deduplication (which drops 4G/5G) is deliberately not reused. */
public final class PhoneIdentityMatcher {
    private PhoneIdentityMatcher() {}
    private static final Pattern GROUP = Pattern.compile("[/\\\\;；|,，、\\n\\r]");
    private static final Pattern BRAND_GROUP = Pattern.compile("[/\\\\;；|,，、\\n\\r=＝]");
    private static final Pattern NON_PHONE = Pattern.compile("挂绳|支架|夹子|配件|按键|治具|转轴压板|转盘[ab]|旋钮|牙套盒|(?i:\\b(?:clip|perilla|botón|ortodoncia)\\b|servicio de impresión|fundas para celular|^portador\\b|^universal.*inch|^modelo común.*pulgada|^heyday.*phone case)",Pattern.CASE_INSENSITIVE);
    /** Explicit product words only; an unrecognized name is never classified by absence of a brand. */
    public static String nonPhoneEvidence(String original) {
        var found=NON_PHONE.matcher(Objects.toString(original,""));
        return found.find()?"原文明确包含配件、通用尺寸或服务描述：“"+found.group()+"”":null;
    }
    public static boolean isSingleModel(String value) {return value!=null&&!value.isBlank()&&!GROUP.matcher(value).find();}
    public record Match(String state,Long phoneModelId,List<Long> candidateIds) {}
    private static String text(Object value) {return Objects.toString(value,"");}
    public static String normalize(String value) {
        return Objects.toString(value,"").toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Zs}]+"," ").strip();
    }
    @SuppressWarnings("unchecked")
    public static Set<String> brandNames(Map<String,Object> brand) {
        var names=new LinkedHashSet<String>();names.add(normalize(text(brand.get("name"))));
        for(Object alias:(List<?>)brand.getOrDefault("aliases",List.of()))names.add(normalize(text(alias)));
        return names;
    }
    public static List<Map<String,Object>> sourceBrands(String sourceBrand,String sourceModel,List<Map<String,Object>> brands) {
        String rawBrand=normalize(sourceBrand),rawModel=normalize(sourceModel);
        var matches=brands.stream().filter(b->"enabled".equals(b.get("status"))).filter(b->brandNames(b).contains(rawBrand)).toList();
        if(matches.isEmpty()&&rawBrand.isEmpty())matches=leadingBrands(rawModel,brands);
        return matches;
    }
    /** Maintained aliases and series prefixes are the sole brand dictionary for every caller. */
    public static Set<String> brandPrefixes(Map<String,Object> brand) {
        var names=new LinkedHashSet<>(brandNames(brand));
        for(var rule:prefixRules(brand))names.add(normalize(text(rule.get("prefix"))));
        names.remove("");return names;
    }
    private static boolean leading(String model,String prefix) {return model.equals(prefix)||model.startsWith(prefix+" ");}
    private static List<Map<String,Object>> leadingBrands(String model,List<Map<String,Object>> brands) {
        var lengths=new LinkedHashMap<Map<String,Object>,Integer>();int longest=0;
        for(var brand:brands)if("enabled".equals(brand.get("status"))) {
            int length=brandPrefixes(brand).stream().filter(p->leading(model,p)).mapToInt(String::length).max().orElse(0);
            if(length>0){lengths.put(brand,length);longest=Math.max(longest,length);}
        }
        final int best=longest;return lengths.entrySet().stream().filter(e->e.getValue()==best).map(Map.Entry::getKey).toList();
    }
    public record BrandReview(String state,List<Long> brandIds,List<String> evidence,String reason) {}
    /** A group can have an unambiguous brand without having a single unambiguous phone identity. */
    public static BrandReview reviewBrand(String original,List<Map<String,Object>> brands) {
        String name=normalize(original);if(name.isBlank())return unresolved("原型号名称为空",List.of());
        var ids=new TreeSet<Long>();var evidence=new ArrayList<String>();String firstModel="";
        var segments=BRAND_GROUP.split(original,-1);
        for(int i=0;i<segments.length;i++) {
            String segment=normalize(segments[i]);if(segment.isEmpty())return unresolved("组合名称包含空白段，须核对原文",evidence);
            var found=leadingBrands(segment,brands);
            if(found.size()>1)return unresolved("前缀“"+segment+"”同时对应多个品牌，请在品牌维护中更正别名或系列前缀",evidence);
            if(found.isEmpty()) {
                if(i==0)return unresolved("未找到明确品牌前缀，请在品牌维护中补充品牌或别名",evidence);
                if(!omittedPrefix(segment,firstModel))return unresolved("组合名称“"+segment+"”缺少可确认的品牌前缀",evidence);
                evidence.add("“"+segment+"”沿用同组明确品牌（省略前缀）");continue;
            }
            var brand=found.get(0);
            String prefix=brandPrefixes(brand).stream().filter(p->leading(segment,p)).max(Comparator.comparingInt(String::length)).orElseThrow();
            firstModel=segment.substring(prefix.length()).strip();
            final String rest=firstModel;final Object initialBrandId=brand.get("brandId");
            // A spelled-out sub-brand is stronger than an old parent-brand prefix. RM is
            // deliberately excluded here: XM RM NOTE is a Redmi series, not Realme.
            var nested=brands.stream().filter(b->"enabled".equals(b.get("status"))&&!Objects.equals(b.get("brandId"),initialBrandId)&&normalize(text(b.get("name"))).length()>=4&&leading(rest,normalize(text(b.get("name"))))).toList();
            if(nested.size()>1)return unresolved("原文同时指向多个完整品牌，请核对品牌身份",evidence);
            if(nested.size()==1){brand=nested.get(0);firstModel=rest.substring(normalize(text(brand.get("name"))).length()).strip();evidence.add("完整品牌“"+brand.get("name")+"”优先于历史父品牌前缀“"+prefix+"”");}
            long id=((Number)brand.get("brandId")).longValue();ids.add(id);
            if(nested.isEmpty())evidence.add("明确前缀“"+prefix+"” → "+text(brand.get("name")));
        }
        return new BrandReview("ready",List.copyOf(ids),List.copyOf(evidence),ids.size()>1?"原文明确列出多个品牌；仅归类，保留组合型号不拆分或合并":"品牌前缀明确；仅确认品牌分类，原型号和组合关系保留");
    }
    private static boolean omittedPrefix(String segment,String firstModel) {
        if(Character.isDigit(segment.charAt(0)))return true;
        if(Pattern.compile("^[a-z]{1,3}[0-9]").matcher(segment).find())return true;
        if(segment.matches("[a-z]{1,3}")||segment.matches("\\++"))return true;
        var words=Pattern.compile("[\\p{L}]+").matcher(firstModel);var known=new HashSet<String>();
        while(words.find())known.add(words.group());
        var leading=Pattern.compile("^[\\p{L}]+").matcher(segment);
        return leading.find()&&known.contains(leading.group());
    }
    private static BrandReview unresolved(String reason,List<String> evidence) {return new BrandReview("unresolved",List.of(),List.copyOf(evidence),reason);}
    /** Only a complete leading token is stripped: IP never matches iPad, and SM-A123 is retained. */
    @SuppressWarnings("unchecked") public static String modelKey(String model,Map<String,Object> brand) {
        String key=normalize(model);
        var rules=prefixRules(brand).stream()
            .sorted(Comparator.comparingInt((Map<String,Object> r)->normalize(text(r.get("prefix"))).length()).reversed()).toList();
        final String original=key;
        if(rules.stream().anyMatch(r->original.startsWith(normalize(text(r.get("prefix")))+" ")))return replacePrefix(key,rules);
        List<String> prefixes=new ArrayList<>(brandNames(brand));prefixes.sort(Comparator.comparingInt(String::length).reversed());
        for(String prefix:prefixes)if(!prefix.isEmpty()&&key.startsWith(prefix+" ")) {
            key=key.substring(prefix.length()).strip();
            break;
        }
        for(var rule:rules)if(key.startsWith(normalize(text(rule.get("prefix")))+" "))return replacePrefix(key,rules);
        // Some imported names repeat two explicit aliases of the same brand, e.g. MT Moto G56.
        for(String prefix:prefixes)if(!prefix.isEmpty()&&key.startsWith(prefix+" ")){key=key.substring(prefix.length()).strip();break;}
        return replacePrefix(key,rules);
    }
    private static String replacePrefix(String key,List<Map<String,Object>> rules) {
        for(var rule:rules){String prefix=normalize(text(rule.get("prefix")));if(!prefix.isBlank()&&key.startsWith(prefix+" "))return (normalize(text(rule.get("replacement")))+" "+key.substring(prefix.length()).strip()).strip();}
        return key;
    }
    /** Bootstrap defaults apply only to pre-migration snapshots without a maintained prefixes field. */
    @SuppressWarnings("unchecked") public static List<Map<String,Object>> prefixRules(Map<String,Object> brand) {
        if(brand.containsKey("prefixes"))return (List<Map<String,Object>>)brand.get("prefixes");
        return switch(normalize(text(brand.get("name")))) {
            case "apple" -> List.of(Map.of("prefix","IP","replacement","iPhone"),Map.of("prefix","iPhone","replacement","iPhone"));
            case "samsung" -> List.of(Map.of("prefix","Galaxy","replacement",""));
            default -> List.of();
        };
    }
    @SuppressWarnings("unchecked")
    public static Match match(String sourceBrand,String sourceModel,List<Map<String,Object>> brands,List<Map<String,Object>> phones) {
        String rawBrand=normalize(sourceBrand),rawModel=normalize(sourceModel);
        if(!isSingleModel(sourceModel))return new Match("unmatched",null,List.of());
        var brandMatches=sourceBrands(sourceBrand,sourceModel,brands);
        Set<Long> ids=new TreeSet<>();
        for(var brand:brandMatches) {
            String key=identityKey(sourceModel,brand);long brandId=((Number)brand.get("brandId")).longValue();
            for(var phone:phones) {
                if(((Number)phone.get("brandId")).longValue()!=brandId||!"enabled".equals(phone.get("status")))continue;
                if(Set.of("missing","disabled").contains(text(phone.get("codeLinkStatus"))))continue;
                var names=new ArrayList<String>();names.add(text(phone.get("modelName")));names.addAll((List<String>)phone.getOrDefault("aliases",List.of()));names.addAll((List<String>)phone.getOrDefault("matchingAliases",List.of()));
                if(names.stream().filter(PhoneIdentityMatcher::isSingleModel).anyMatch(name->key.equals(identityKey(name,brand))))ids.add(((Number)phone.get("phoneModelId")).longValue());
            }
        }
        return new Match(ids.size()==1?"matched":ids.isEmpty()?"unmatched":"ambiguous",ids.size()==1?ids.iterator().next():null,List.copyOf(ids));
    }
    /** Whitespace is presentation; generation, network, region and hole descriptions remain in the key. */
    public static String identityKey(String model,Map<String,Object> brand) {return modelKey(model,brand).replace(" ","");}
}
