package com.yuewei.plm.module.collaboration;

import com.yuewei.plm.module.moldreference.PhoneIdentityMatcher;
import java.util.*;
import java.util.regex.*;

/** Matches the existing physical set, never creates phones, fit evidence, or tooling. */
public final class MoldScheduleSheetMatcher {
    private MoldScheduleSheetMatcher() {}
    public record Match(Long moldSetId,List<Long> candidates,String reason) {}
    public static Match match(MoldScheduleSheetParser.SourceRow source,List<Map<String,Object>> sets,List<Map<String,Object>> brands,Map<String,String> styleAliases) {
        var matchingBrands=PhoneIdentityMatcher.sourceBrands(source.brand(),source.model(),brands);
        if(matchingBrands.size()!=1)return new Match(null,List.of(),"品牌未唯一对应现有字典");
        var brand=matchingBrands.get(0);String style=null;
        for(var alias:styleAliases.entrySet())if(normalize(alias.getKey()).equals(normalize(source.style())))style=alias.getValue();
        final String code=style;
        boolean copy=source.model().matches(".*(复制|重新开模).*" );
        String key=modelGroup(source.model(),brand);
        if(key.isEmpty())return new Match(null,List.of(),"型号或兼容组合不明确");
        var candidates=sets.stream().filter(s->code!=null?code.equals(s.get("styleCode")):normalize(source.style()).equals(normalize(s.get("styleName"))))
            .filter(s->key.equals(modelGroup(Objects.toString(s.get("compatibleModels"),""),brand)))
            .filter(s->copy==(s.get("sourceMoldSetId")!=null||Objects.toString(s.get("setName"),"").contains("复制")))
            .map(s->((Number)s.get("moldSetId")).longValue()).distinct().sorted().toList();
        return new Match(candidates.size()==1?candidates.get(0):null,candidates,candidates.size()==1?"现有产品编码、完整型号组合与实物套类型一致":candidates.isEmpty()?"尚无唯一实物套对应，需选择已有套核对":"同一身份存在多套实物，需明确本套");
    }
    static String normalize(Object s){return MoldScheduleSheetParser.normalize(Objects.toString(s,""));}
    static String modelGroup(String raw,Map<String,Object> brand) {
        raw=raw.replaceAll("[（(][^）)]*(?:复制|重新开模)[^）)]*[）)]","");
        var prefixes=PhoneIdentityMatcher.brandPrefixes(brand).stream().map(MoldScheduleSheetMatcher::normalize).filter(p->p.length()>1).sorted(Comparator.comparingInt(String::length).reversed()).toList();
        List<String> parts=new ArrayList<>();String initial="";
        for(String segment:raw.split("[/／;；、]",-1)) {
            String key=normalize(segment);if(key.isBlank())return "";
            // Only maintained brand/series aliases are removed. Network and Pro/Max remain.
            for(int n=0;n<2;n++){String old=key;for(String prefix:prefixes)if(key.startsWith(prefix)){key=key.substring(prefix.length());break;}if(old.equals(key))break;}
            if(key.isBlank())return "";
            if(parts.isEmpty())initial=key;
            else {
                Matcher first=Pattern.compile("^[^0-9]*").matcher(initial);first.find();String firstSeries=first.group();
                Matcher second=Pattern.compile("^[^0-9]*").matcher(key);second.find();String secondSeries=second.group();
                if(key.matches("[45]g")&&initial.matches(".*[45]g$"))key=initial.replaceFirst("[45]g$",key);
                else if(firstSeries.endsWith(secondSeries))key=firstSeries.substring(0,firstSeries.length()-secondSeries.length())+key;
            }
            parts.add(key);
        }
        return String.join("/",new TreeSet<>(parts));
    }
}
