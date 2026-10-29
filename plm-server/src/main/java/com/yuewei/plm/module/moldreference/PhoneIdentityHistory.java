package com.yuewei.plm.module.moldreference;

import java.util.*;
import java.util.regex.Pattern;

/** Conservative preview of existing code anchors. It neither allocates codes nor confirms compatibility. */
public final class PhoneIdentityHistory {
    private PhoneIdentityHistory() {}
    private static final Pattern HOLE=Pattern.compile("(?i)(agujero|orificio|hoyo|hole|孔|洞|fno)");
    private static final Pattern OTHER_DEVICE=Pattern.compile("(?i)\\b(ipad|matepad|pad|tab|tablet|watch|buds|inch)\\b");
    private static final Set<String> WORDS=Set.of("z","iphone","galaxy","moto","redmi","poco","mi","rm","note","mate","nova","honor","ascend","reno","find","edge","razr","nord","realme","iqoo","blade","nubia","redmagic","pro","max","plus","ultra","lite","mini","se","fe","neo","prime","power","play","stylus","fusion","zoom","air","rs","gt","turbo","fold","flip","duos","ace","core","grand","young","compact","xl","mix","global","mx","cn","kr","eu","us","latam");
    public record Decision(long codeItemId,String codeValue,String originalName,Long brandId,String identityKey,String action,String reason,Long existingPhoneModelId) {}
    public record Preview(List<Decision> decisions,Map<String,Long> counts) {}
    /** Only the already verified MES source ID may contribute its explicit prefix; collisions are rejected. */
    @SuppressWarnings("unchecked") public static List<Map<String,Object>> withVerifiedSourceAliases(List<Map<String,Object>> brands) {
        var result=brands.stream().map(LinkedHashMap<String,Object>::new).toList();
        for(var brand:result) {
            if(!"mes".equals(brand.get("sourceSystem"))||!(brand.get("sourceSnapshot") instanceof Map<?,?> source))continue;
            String prefix=Objects.toString(source.get("brand_code"),"").strip(),sourceId=Objects.toString(source.get("id"),"");
            if(prefix.isBlank()||!prefix.matches("[A-Za-z][A-Za-z0-9]{0,31}")||!sourceId.equals(Objects.toString(brand.get("sourceRecordId"))))continue;
            String normalized=PhoneIdentityMatcher.normalize(prefix);
            boolean collision=result.stream().filter(b->!Objects.equals(b.get("brandId"),brand.get("brandId"))).anyMatch(b->PhoneIdentityMatcher.brandNames(b).contains(normalized));
            long sourceCount=brands.stream().filter(b->b.get("sourceSnapshot") instanceof Map<?,?> snapshot&&normalized.equals(PhoneIdentityMatcher.normalize(Objects.toString(snapshot.get("brand_code"),"")))).count();
            if(collision||sourceCount!=1)continue;
            var aliases=new ArrayList<String>((List<String>)brand.getOrDefault("aliases",List.of()));if(!PhoneIdentityMatcher.brandNames(brand).contains(normalized))aliases.add(prefix);brand.put("aliases",aliases);
        }
        return new ArrayList<>(result);
    }
    public static Preview preview(List<Map<String,Object>> brands,List<Map<String,Object>> phones,List<Map<String,Object>> codes) {
        var decisions=new ArrayList<Decision>();var keys=new HashMap<String,List<Integer>>();
        for(var code:codes) {
            long id=((Number)code.get("codeItemId")).longValue();String value=text(code,"codeValue"),name=text(code,"codeName");String reason=null;Long brandId=null;String key=null;Long existing=null;
            if(!"enabled".equals(code.get("status"))||!value.matches("[0-9]{4}"))reason="disabled_or_invalid_code";
            else if(!PhoneIdentityMatcher.isSingleModel(name))reason="combined_model";
            else if(HOLE.matcher(name).find())reason="hole_scope";
            else if(OTHER_DEVICE.matcher(name).find())reason="other_device";
            else {
                var matches=PhoneIdentityMatcher.sourceBrands("",name,brands);
                if(matches.size()!=1)reason="brand_prefix_unresolved";
                else {
                    var brand=matches.get(0);brandId=((Number)brand.get("brandId")).longValue();key=PhoneIdentityMatcher.modelKey(name,brand);
                    if(!recognizedModel(key,brand))reason="model_or_suffix_unrecognized";
                    else {
                        var same=phones.stream().filter(p->((Number)p.get("brandId")).longValue()==((Number)brand.get("brandId")).longValue())
                            .filter(p->phoneNames(p).stream().anyMatch(n->keyEquals(n,name,brand))).toList();
                        if(same.size()>1)reason="existing_identity_ambiguous";
                        else if(same.size()==1) {
                            var phone=same.get(0);if(sameId(phone.get("codeItemId"),id))existing=((Number)phone.get("phoneModelId")).longValue();else reason="existing_identity_other_code";
                        } else if(phones.stream().anyMatch(p->sameId(p.get("codeItemId"),id)))reason="existing_code_has_different_member";
                    }
                }
            }
            String action=reason==null?(existing==null?"initialize":"existing"):"review";
            int index=decisions.size();decisions.add(new Decision(id,value,name,brandId,key,action,reason,existing));
            if(brandId!=null&&key!=null&&reason==null)keys.computeIfAbsent(brandId+":"+key,k->new ArrayList<>()).add(index);
        }
        // No first-row choice: every different code for a normalized identity remains for review.
        for(var indices:keys.values())if(indices.size()>1)for(int index:indices) {
            var old=decisions.get(index);decisions.set(index,new Decision(old.codeItemId(),old.codeValue(),old.originalName(),old.brandId(),old.identityKey(),"review","multiple_code_identity",old.existingPhoneModelId()));
        }
        var counts=new LinkedHashMap<String,Long>();counts.put("total",(long)decisions.size());for(var decision:decisions)counts.merge(decision.action().equals("review")?decision.reason():decision.action(),1L,Long::sum);
        return new Preview(List.copyOf(decisions),counts);
    }
    @SuppressWarnings("unchecked") private static List<String> phoneNames(Map<String,Object> phone) {var names=new ArrayList<String>();names.add(text(phone,"modelName"));names.addAll((List<String>)phone.getOrDefault("aliases",List.of()));return names;}
    private static boolean keyEquals(String name,String other,Map<String,Object> brand) {return PhoneIdentityMatcher.isSingleModel(name)&&PhoneIdentityMatcher.modelKey(name,brand).equals(PhoneIdentityMatcher.modelKey(other,brand));}
    public static boolean recognizedModel(String key,Map<String,Object> brand) {
        if(key.isBlank()||!key.matches("[a-z0-9 .+_-]+")||!key.matches(".*[0-9].*")&&!key.matches("iphone (x|xr|xs)( (pro|max|plus))*"))return false;
        String canonical=PhoneIdentityMatcher.normalize(text(brand,"name"));String[] tokens=key.replace("+"," plus ").split(" +");
        if(canonical.equals("apple")&&!key.matches("iphone (?:[0-9]{1,2}[a-z]?|x|xr|xs|se)(?: .*)?"))return false;
        if(canonical.equals("huawei")&&!key.matches("(?:mate|nova|honor|ascend|[pyg](?:r|w)?[0-9]+[a-z]*)(?: .*)?"))return false;
        if(canonical.equals("samsung")&&!key.matches("(?:[asmfjc][0-9]{1,2}[a-z]*|note|z|grand|core)(?: .*)?"))return false;
        for(String token:tokens)if(!WORDS.contains(token)&&!token.matches("[a-z]*[0-9]+(?:\\.[0-9]+)?[a-z]*"))return false;
        return true;
    }
    private static boolean sameId(Object value,long id) {return value instanceof Number number&&number.longValue()==id;}
    private static String text(Map<String,Object> value,String key) {return Objects.toString(value.get(key),"").strip();}
}
