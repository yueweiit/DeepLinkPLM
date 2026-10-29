package com.yuewei.plm.module.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import static com.yuewei.plm.module.collaboration.CollaborationFields.invalid;

/** The verified Chinese/Spanish sheet is parsed by headers, with formulas kept as forecasts. */
public final class MoldScheduleSheetParser {
    private MoldScheduleSheetParser() {}
    public record SourceRow(int rowNo,String key,String style,String brand,String model,Map<String,String> dates,String notes,boolean arrivalFormula,List<String> issues) {}
    public static List<SourceRow> parse(JsonNode source) {
        JsonNode values=source.path("displayValues").isArray()?source.path("displayValues"):source.path("values");
        if(!values.isArray())throw invalid("在线表为空，请核对后再同步");
        if(values.size()>=1000&&values.path(999).isArray()&&java.util.stream.StreamSupport.stream(values.path(999).spliterator(),false).anyMatch(c->!c.asText("").isBlank()))throw invalid("在线表超过本次完整读取上限，请核对后再同步");
        int header=-1;
        Map<String,Integer> columns=new HashMap<>();
        for(int r=0;r<Math.min(values.size(),10);r++) {
            Map<String,Integer> candidate=new HashMap<>();
            for(int c=0;c<values.path(r).size();c++) {
                String label=values.path(r).path(c).asText().replaceAll("\\s+","");
                for(String name:List.of("序号","款式","品牌","型号","开模日期","计划模具交付日期","计划到达工厂日期","实际发模日期","抵达墨西哥","观察","备注"))
                    if(label.startsWith(name))candidate.put(name,c);
            }
            if(candidate.keySet().containsAll(List.of("款式","品牌","型号","开模日期","计划模具交付日期","计划到达工厂日期","实际发模日期","抵达墨西哥"))){header=r;columns=candidate;break;}
        }
        if(header<0)throw invalid("在线表表头与已确认结构不同，未修改排程");
        List<SourceRow> rows=new ArrayList<>();Set<String> seen=new HashSet<>();
        var mapping=Map.of("开模日期","openedDate","计划模具交付日期","plannedMoldDeliveryDate","计划到达工厂日期","forecastFactoryDate","实际发模日期","actualShipDate","抵达墨西哥","actualMexicoArrivalDate");
        for(int r=header+1;r<values.size();r++) {
            JsonNode row=values.path(r);String style=cell(row,columns,"款式"),model=cell(row,columns,"型号"),brand=cell(row,columns,"品牌");
            if(style.isBlank()&&model.isBlank())continue;
            List<String> issues=new ArrayList<>();Map<String,String> dates=new LinkedHashMap<>();
            if(style.isBlank()||model.isBlank())issues.add("款式或型号缺失");
            String key=hash(normalize(style)+"|"+normalize(brand)+"|"+normalize(model));
            if(!seen.add(key))issues.add("在线表存在重复款式型号，请先核对具体实物套");
            for(var entry:mapping.entrySet()) {
                String raw=cell(row,columns,entry.getKey());if(missing(raw))continue;
                try {dates.put(entry.getValue(),date(raw).toString());}catch(RuntimeException failure){issues.add(entry.getKey()+"不是明确日期："+raw);}
            }
            String formula=source.path("formulas").path(r).path(columns.get("计划到达工厂日期")).asText();
            String expectedFormula="="+(char)('A'+columns.get("计划模具交付日期"))+(r+1)+"+7";
            boolean arrivalFormula=!formula.isBlank()&&formula.replace("$","").replaceAll("\\s+","").equalsIgnoreCase(expectedFormula);
            if(!formula.isBlank()&&!arrivalFormula)issues.add("计划到达工厂公式已改变，请核对后再采用本行");
            String notes=cell(row,columns,"观察");String more=cell(row,columns,"备注");
            if(!more.isBlank())notes+=(notes.isBlank()?"":"\n")+more;
            rows.add(new SourceRow(r+1,key,style,brand,model,Map.copyOf(dates),notes,arrivalFormula,List.copyOf(issues)));
        }
        if(rows.isEmpty())throw invalid("没有有效开模记录，未修改排程");
        var duplicateKeys=new HashSet<String>();var counts=new HashMap<String,Integer>();rows.forEach(r->counts.merge(r.key(),1,Integer::sum));counts.forEach((k,n)->{if(n>1)duplicateKeys.add(k);});
        return rows.stream().map(r->{if(!duplicateKeys.contains(r.key())||!r.issues().isEmpty())return r;var issues=new ArrayList<>(r.issues());issues.add("在线表存在重复款式型号，请先核对具体实物套");return new SourceRow(r.rowNo(),r.key(),r.style(),r.brand(),r.model(),r.dates(),r.notes(),r.arrivalFormula(),issues);}).toList();
    }
    static String cell(JsonNode row,Map<String,Integer> columns,String name){Integer c=columns.get(name);return c==null?"":row.path(c).asText("").strip();}
    static boolean missing(String s){return s.isBlank()||Set.of("暂无数据","暂无","待定","—","-","/").contains(s);}
    static LocalDate date(String s) {
        s=s.strip();
        for(String pattern:List.of("uuuu-M-d","uuuu/M/d","d/M/uuuu","d-M-uuuu"))try{return LocalDate.parse(s,DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT));}catch(DateTimeParseException ignored){}
        try {return LocalDateTime.parse(s.replace(' ','T')).toLocalDate();}catch(DateTimeParseException ignored){}
        if(s.matches("[0-9]{5}(?:\\.0+)?")){long days=(long)Double.parseDouble(s);return LocalDate.of(1899,12,30).plusDays(days);}
        throw invalid("无效日期");
    }
    static String normalize(String s){return java.text.Normalizer.normalize(Objects.toString(s,""),java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");}
    /** Save business evidence, not thousands of padded formatting/alignment cells. */
    static JsonNode retainedSnapshot(JsonNode source){
        var result=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        var values=source.path("displayValues").isArray()?source.path("displayValues"):source.path("values");int end=0;
        for(int r=0;r<values.size();r++)for(JsonNode cell:values.path(r))if(!cell.asText("").isBlank()){end=r+1;break;}
        for(String key:List.of("displayValues","values","formulas"))if(source.path(key).isArray()){var array=result.putArray(key);for(int r=0;r<Math.min(end,source.path(key).size());r++)array.add(source.path(key).path(r));}
        return result;
    }
    static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
