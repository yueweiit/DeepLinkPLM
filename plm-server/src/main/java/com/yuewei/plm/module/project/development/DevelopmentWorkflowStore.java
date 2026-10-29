package com.yuewei.plm.module.project.development;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.constant.ErrorCodeConstants;
import com.yuewei.plm.common.exception.BusinessException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Persisted draft plus append-only confirmed facts; caller holds the project's write lock. */
@Component
@RequiredArgsConstructor
public class DevelopmentWorkflowStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public Map<String,Object> load(long id) {
        var rows=jdbc.queryForList("select version,data::text from plm_product_development_state where product_id=?",id);
        Map<String,Object> state=rows.isEmpty()?new LinkedHashMap<>():map(read(rows.get(0).get("data")));
        state.put("version",rows.isEmpty()?0L:rows.get(0).get("version"));
        return state;
    }
    public void check(Map<String,Object> state,Long expected) {
        if(expected==null&&number(state.get("version"))>0)throw invalid("请刷新新版页面后保存，避免覆盖已有草稿");
        if(expected!=null&&number(state.get("version"))!=expected)throw invalid("项目已被其他操作更新，请重新载入后核对；本次输入尚未覆盖服务器");
    }
    public void save(long id,Map<String,Object> state) {
        long next=number(state.get("version"))+1; state.put("version",next);
        jdbc.update("insert into plm_product_development_state(product_id,version,data) values(?,?,?::jsonb) on conflict(product_id) do update set version=excluded.version,data=excluded.data,updated_at=now()",id,next,encode(state));
    }
    public boolean repeated(long id,String request) {
        return request!=null&&!request.isBlank()&&Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from plm_product_development_event where product_id=? and request_id=?)",Boolean.class,id,request));
    }
    public long event(long id,String kind,Object data,String request,String actor) {
        return jdbc.queryForObject("insert into plm_product_development_event(product_id,kind,data,request_id,created_by) values(?,?,?::jsonb,?,?) returning event_id",Long.class,id,kind,encode(data),request,actor);
    }
    public List<Map<String,Object>> events(long id) {
        var rows=jdbc.queryForList("""
            select event_id as "eventId",kind,data::text,created_at as "createdAt",created_by as "createdBy"
            from plm_product_development_event where product_id=? order by event_id desc
            """,id);
        rows.forEach(r->r.put("data",read(r.get("data"))));return rows;
    }
    public String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("研发资料无法保存",e);}}
    public Object read(Object value){try{return json.readValue(Objects.toString(value,"null"),new TypeReference<Object>(){});}catch(Exception e){throw new IllegalStateException("研发资料无法读取",e);}}
    @SuppressWarnings("unchecked") public static Map<String,Object> map(Object v){return v instanceof Map<?,?>?(Map<String,Object>)v:new LinkedHashMap<>();}
    public static long number(Object v){try{return v==null?0:Long.parseLong(v.toString());}catch(NumberFormatException e){throw invalid("引用编号或数量格式无效");}}
    public static String text(Object v){return Objects.toString(v,"").trim();}
    public static BusinessException invalid(String message){return new BusinessException(ErrorCodeConstants.VALIDATION_ERROR,message);}
}
