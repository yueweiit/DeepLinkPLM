package com.yuewei.plm.module.marketwatch;

import java.time.*;
import java.util.*;

/** Exposes only model-verified full prices, and keeps raw historical values separately. */
final class MarketPhonePriceProjection {
    private MarketPhonePriceProjection(){}
    static boolean http(Object value){try{var u=java.net.URI.create(Objects.toString(value,""));return Set.of("http","https").contains(u.getScheme())&&u.getHost()!=null&&u.getUserInfo()==null;}catch(Exception e){return false;}}
    @SuppressWarnings("unchecked") static void apply(Map<String,Object> row){
        var quotes=new ArrayList<Map<String,Object>>();var links=new LinkedHashMap<String,Map<String,Object>>();var history=new ArrayList<Map<String,Object>>();var errors=new ArrayList<Map<String,Object>>();
        for(var s:(List<Map<String,Object>>)row.getOrDefault("sources",List.of())){
            if(http(s.get("source_url")))links.putIfAbsent(s.get("source_url").toString(),Map.of("url",s.get("source_url"),"source",Objects.toString(s.get("source_name"),"市场来源"),"kind","source","model",Objects.toString(s.get("model"),"")));
            if(s.get("price_error")!=null&&!s.get("price_error").toString().isBlank())errors.add(Map.of("source",Objects.toString(s.get("source_name"),""),"message",s.get("price_error"),"checkedAt",Objects.toString(s.get("price_checked_at"),"")));
            Object raw=s.get("price_evidence");if(!(raw instanceof Map<?,?>))continue;var primary=(Map<String,Object>)raw;var candidates=new ArrayList<Map<String,Object>>();candidates.add(primary);if(primary.get("alternativeQuote") instanceof Map<?,?> alternative)candidates.add((Map<String,Object>)alternative);
            for(var e:candidates){
            if(!Boolean.TRUE.equals(e.get("verified"))||!(e.get("amount") instanceof Number)||!http(e.get("url")))continue;
            var q=new LinkedHashMap<String,Object>(e);q.put("source",Objects.toString(e.get("source"),Objects.toString(s.get("source_name"),"市场来源")));q.put("country",Objects.toString(e.get("country"),Objects.toString(s.get("country"),Objects.toString(s.get("source_country"),""))));q.put("error",e==primary?s.get("price_error"):null);
            boolean stale=true;try{stale=OffsetDateTime.parse(e.get("capturedAt").toString()).toInstant().isBefore(Instant.now().minus(Duration.ofDays(7)));}catch(Exception ignored){}
            q.put("stale",stale);quotes.add(q);links.putIfAbsent(e.get("url").toString(),Map.of("url",e.get("url"),"source",q.get("source"),"kind","quote"));
            }
        }
        for(var g:(List<Map<String,Object>>)row.getOrDefault("coverages",List.of()))for(var app:(List<Map<String,Object>>)g.getOrDefault("applications",List.of())){
            if(http(app.get("referenceUrl")))links.putIfAbsent(app.get("referenceUrl").toString(),Map.of("url",app.get("referenceUrl"),"source","申请 #"+app.get("applicationId"),"kind","application"));
            if(app.get("historicalPrice")!=null&&!app.get("historicalPrice").toString().isBlank()){var p=new LinkedHashMap<String,Object>();p.put("applicationId",app.get("applicationId"));p.put("price",app.get("historicalPrice"));p.put("currency",app.get("historicalPriceCurrency"));p.put("recordedAt",app.get("sourceCreatedAt"));history.add(p);}
        }
        for(var g:(List<Map<String,Object>>)row.getOrDefault("coverages",List.of()))for(var mold:(List<Map<String,Object>>)g.getOrDefault("molds",List.of()))if(http(mold.get("referenceUrl")))links.putIfAbsent(mold.get("referenceUrl").toString(),Map.of("url",mold.get("referenceUrl"),"source","市场资料主档 #"+mold.get("itemId"),"kind","archive"));
        quotes.sort(Comparator.<Map<String,Object>,Integer>comparing(q->"MXN".equals(q.get("currency"))&&"MX".equals(q.get("country"))?0:"CNY".equals(q.get("currency"))?1:2).thenComparing(q->Boolean.TRUE.equals(q.get("stale"))?1:0).thenComparing(q->"retail".equals(q.get("kind"))?0:1).thenComparing(q->Objects.toString(q.get("capturedAt"),""),Comparator.reverseOrder()));
        var unique=new LinkedHashMap<List<String>,Map<String,Object>>();for(var q:quotes)unique.putIfAbsent(List.of(Objects.toString(q.get("currency"),""),Objects.toString(q.get("amount"),""),Objects.toString(q.get("specs"),""),Objects.toString(q.get("url"),"")),q);quotes.clear();quotes.addAll(unique.values());
        row.put("prices",quotes);row.put("preferredPrice",quotes.isEmpty()?null:quotes.get(0));row.put("mexicoPrice",quotes.stream().filter(q->"MXN".equals(q.get("currency"))&&"MX".equals(q.get("country"))).findFirst().orElse(null));row.put("referenceLinks",List.copyOf(links.values()));row.put("historicalPrices",history);row.put("priceErrors",errors);
    }
}
