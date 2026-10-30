package com.yuewei.plm.module.moldreference;

import java.util.*;

/** Presentation only: neither spelling changes a member, registered code or historical snapshot. */
public final class PhoneDisplayNames {
    private PhoneDisplayNames() {}
    public record Prefix(String prefix,String replacement) {}

    public static String fullName(String model,String brand,List<String> aliases,List<Prefix> prefixes) {
        String name=model.strip(),fullBrand=fullBrand(brand,aliases);
        if(name.isEmpty())return fullBrand;
        if(leading(name,fullBrand))return name;
        for(var rule:longestFirst(prefixes))if(leading(name,rule.prefix())) {
            // Empty replacements are identity-search rules (Galaxy is not disposable display text).
            if(rule.replacement().isBlank())continue;
            return join(fullBrand,join(rule.replacement(),tail(name,rule.prefix())));
        }
        var names=new ArrayList<>(aliases.stream().filter(PhoneDisplayNames::shortCode).toList());names.add(brand);
        names.sort(Comparator.comparingInt(String::length).reversed());
        for(String alias:names)if(leading(name,alias))return join(fullBrand,tail(name,alias));
        return join(fullBrand,name);
    }

    public static String factoryName(String original,String brand,List<String> aliases,List<Prefix> prefixes) {
        String name=original.strip();
        if(name.isEmpty())return name;
        var shortNames=new ArrayList<String>();
        if(shortCode(brand))shortNames.add(brand);
        aliases.stream().filter(PhoneDisplayNames::shortCode).forEach(shortNames::add);
        for(String alias:aliases)if(alias.matches("[A-Z]{2,4}")&&!alias.equalsIgnoreCase(brand)&&leading(name,alias))return name;
        for(String code:shortNames)if(leading(name,code))return name;
        for(var rule:longestFirst(prefixes))if(!rule.replacement().isBlank()&&!rule.prefix().equalsIgnoreCase(rule.replacement())&&leading(name,rule.prefix()))return name;
        String fullBrand=fullBrand(brand,aliases),rest=name;
        if(leading(rest,fullBrand))rest=tail(rest,fullBrand);
        else if(leading(rest,brand))rest=tail(rest,brand);
        for(var rule:longestFirst(prefixes))if(!rule.replacement().isBlank()&&!rule.prefix().equalsIgnoreCase(rule.replacement())&&leading(rest,rule.replacement()))
            return join(rule.prefix(),tail(rest,rule.replacement()));
        return shortNames.isEmpty()?name:join(shortNames.get(0),rest);
    }

    private static String fullBrand(String brand,List<String> aliases) {
        if(!shortCode(brand))return brand;
        var full=aliases.stream().filter(a->a.matches("[A-Za-z]{4,}")).distinct().toList();
        return full.size()==1?full.get(0):brand;
    }
    private static boolean shortCode(String value) {return value.matches("[A-Z]{2,3}");}
    private static boolean leading(String value,String prefix) {
        return !prefix.isBlank()&&(value.equalsIgnoreCase(prefix)||value.length()>prefix.length()&&value.regionMatches(true,0,prefix,0,prefix.length())&&Character.isWhitespace(value.charAt(prefix.length())));
    }
    private static String tail(String value,String prefix) {return value.substring(prefix.length()).stripLeading();}
    private static String join(String first,String second) {return (first+" "+second).strip();}
    private static List<Prefix> longestFirst(List<Prefix> prefixes) {
        return prefixes.stream().sorted(Comparator.comparingInt((Prefix p)->p.prefix().length()).reversed()).toList();
    }
}
