package com.yuewei.plm.module.productionmetadata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.module.code.vo.CodeItemVO;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** One effective brand relation for every reader; manual links do not replace member facts. */
@Repository @RequiredArgsConstructor
public class PhoneCodeMetadataRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public void enrich(List<CodeItemVO> codes) {
        var ids=codes.stream().filter(c->"phone_model".equals(c.getCodeType())).map(CodeItemVO::getCodeItemId).toList();
        if(ids.isEmpty())return;
        String marks=String.join(",",Collections.nCopies(ids.size(),"?"));
        Map<Long,List<Map<String,Object>>> brands=new HashMap<>();
        jdbc.query("select e.code_item_id,jsonb_agg(jsonb_build_object('brandId',b.id,'name',b.name,'status',b.status,'aliases',b.aliases,'prefixes',b.prefixes,'memberConfirmed',exists(select 1 from plm_phone_model_member m where m.code_item_id=e.code_item_id and m.brand_id=b.id)) order by b.name,b.id)::text data from plm_phone_code_brand_effective e join plm_phone_brand b on b.id=e.brand_id where e.code_item_id in ("+marks+") group by e.code_item_id",rs->{
            try{brands.put(rs.getLong("code_item_id"),json.readValue(rs.getString("data"),new TypeReference<>(){}));}
            catch(Exception failure){throw new IllegalStateException("编码组品牌解析失败",failure);}
        },ids.toArray());
        for(var c:codes)if("phone_model".equals(c.getCodeType())) {
            var links=brands.getOrDefault(c.getCodeItemId(),List.of());
            c.setFactoryName(c.getCodeName());
            if(links.size()==1) {
                var b=links.get(0);
                c.setFactoryName(com.yuewei.plm.module.moldreference.PhoneDisplayNames.factoryName(c.getCodeName(),b.get("name").toString(),
                    json.convertValue(b.get("aliases"),new TypeReference<List<String>>(){}),
                    json.convertValue(b.get("prefixes"),new TypeReference<List<com.yuewei.plm.module.moldreference.PhoneDisplayNames.Prefix>>(){})));
            }
            c.setBrands(links.stream().map(b->{var link=new LinkedHashMap<>(b);link.remove("aliases");link.remove("prefixes");return (Map<String,Object>)link;}).toList());
        }
    }
    public List<Long> brandIds(long code,boolean membersOnly) {
        return jdbc.queryForList("select distinct brand_id from "+(membersOnly?"plm_phone_model_member":"plm_phone_code_brand_effective")+" where code_item_id=? order by brand_id",Long.class,code);
    }
    public Map<String,Object> phone(long code,boolean lock) {
        var rows=jdbc.queryForList("select code_item_id as id,code_value as \"codeValue\",code_name as \"codeName\",production_status as \"productionStatus\" from plm_code_item where code_item_id=? and code_type='phone_model' and deleted_flag=0"+(lock?" for update":""),code);
        if(rows.isEmpty())return null;
        var row=rows.get(0);row.put("brandIds",brandIds(code,false));row.put("memberBrandIds",brandIds(code,true));return row;
    }
    /** This scope intentionally ignores UI paging, filters and production status. */
    public List<Map<String,Object>> unclassifiedPhones() {
        return jdbc.queryForList("""
            select c.code_item_id as id,c.code_value as "codeValue",c.code_name as "codeName"
            from plm_code_item c where c.code_type='phone_model' and c.deleted_flag=0
              and not exists(select 1 from plm_phone_code_brand_effective e where e.code_item_id=c.code_item_id)
            order by c.code_value desc,c.code_item_id
            """);
    }
}
