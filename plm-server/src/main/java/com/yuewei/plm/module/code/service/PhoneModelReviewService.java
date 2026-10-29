package com.yuewei.plm.module.code.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.vo.PageVO;
import com.yuewei.plm.module.code.dto.PhoneModelReviewQueryDTO;
import com.yuewei.plm.module.code.dto.PhoneModelReviewConfirmDTO;
import com.yuewei.plm.module.code.vo.PhoneModelReviewVO;
import com.yuewei.plm.module.moldreference.PhoneIdentityMatcher;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceService;
import com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Array;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Historical text remains immutable. A source-bound, audited decision explains legitimate differences. */
@Service @RequiredArgsConstructor
public class PhoneModelReviewService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PilotAccessService access;
    private final PhoneModelReferenceService phones;
    private final OperationLogService audit;
    private static final Set<String> CONCLUSIONS=Set.of("same_phone_alias","shared_code_distinct_phones","shared_mold_different_codes","combined_original_preserved");
    private void read(){access.requireDomesticView();access.requirePermission("collaboration:view");}
    private static boolean byCode(String kind){if(kind==null||!Set.of("code_many_names","name_many_codes").contains(kind))throw invalid("请选择历史核对类别");return kind.equals("code_many_names");}
    private static String keySql(String kind,String alias){return byCode(kind)?"btrim("+alias+".phone_model_code)":"lower(btrim("+alias+".model))";}
    private String cte(String kind) {
        boolean code=byCode(kind);String group=code?"code_value":"normalized_name",distinct=code?"normalized_name":"code_value";
        String visible=access.visibleSqlClause("product","p.product_id"),hidden=access.visibleSqlClause("product","hidden.product_id");
        return """
            with source as (
              select p.product_id,p.product_type,p.product_name,p.model original_model,p.phone_model_code original_phone_code,p.phone_model_member_id,
                btrim(p.phone_model_code) code_value,btrim(p.model) model_name,lower(btrim(p.model)) normalized_name,
                case when pm.id is null then null when lower(pm.model_name)=lower(b.name) or left(lower(pm.model_name),length(b.name)+1) in (lower(b.name)||' ',lower(b.name)||'-',lower(b.name)||':',lower(b.name)||'/') then pm.model_name else concat_ws(' ',b.name,pm.model_name) end current_phone_name
              from plm_product p left join plm_phone_model_member pm on pm.id=p.phone_model_member_id left join plm_phone_brand b on b.id=pm.brand_id
              where p.deleted_flag=0 and nullif(btrim(p.phone_model_code),'') is not null and nullif(btrim(p.model),'') is not null and (%s)
            ), conflicts as (
              select %s as key,array_agg(distinct code_value order by code_value desc) code_values,array_agg(distinct model_name order by model_name) model_names,count(*) source_count,
                md5(jsonb_agg(jsonb_build_array(product_id,product_type,original_model,original_phone_code,phone_model_member_id) order by product_id)::text) source_digest,
                jsonb_agg(jsonb_build_object('projectId',product_id,'productType',product_type,'productName',product_name,'model',original_model,'phoneModelCode',original_phone_code,'phoneModelId',phone_model_member_id,'currentPhoneName',current_phone_name) order by product_id)::text references_json
              from source group by %s having count(distinct %s)>1
            ), reviews as (
              select distinct on(detail_json::jsonb->>'key',detail_json::jsonb->>'sourceDigest') detail_json::jsonb details,operator_user_name,created_at
              from plm_operation_log where business_type='PHONE_MODEL_REVIEW' and business_code=? and action='CONFIRM_HISTORY' and result='success' and deleted_flag=0
              order by detail_json::jsonb->>'key',detail_json::jsonb->>'sourceDigest',created_at desc,log_id desc
            ), groups as (
              select c.*,case when r.details is null then 'pending' else 'confirmed' end review_state,r.details->>'conclusion' conclusion,r.details->>'reason' review_reason,
                r.created_at reviewed_at,r.operator_user_name reviewed_by,coalesce(r.details->'phoneModelIds','[]'::jsonb)::text review_phone_ids,
                not exists(select 1 from plm_product hidden where hidden.deleted_flag=0 and nullif(btrim(hidden.phone_model_code),'') is not null and nullif(btrim(hidden.model),'') is not null and %s=c.key and not (%s)) all_references_visible
              from conflicts c left join reviews r on r.details->>'key'=c.key and r.details->>'sourceDigest'=c.source_digest
            )
            """.formatted(visible,group,group,distinct,keySql(kind,"hidden"),hidden);
    }
    @Transactional(readOnly=true) public PageVO<PhoneModelReviewVO> page(PhoneModelReviewQueryDTO query) {
        read();if(query.getPage()<1||query.getSize()<1||query.getSize()>100)throw invalid("分页参数无效");
        var args=new ArrayList<Object>();args.add(query.getKind());String filter=" where true";
        if(query.getKeyword()!=null&&!query.getKeyword().isBlank()){filter+=" and (key ilike ? or array_to_string(code_values,' ') ilike ? or array_to_string(model_names,' ') ilike ?)";String pattern="%"+query.getKeyword().strip().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";for(int n=0;n<3;n++)args.add(pattern);}
        if(query.getReviewState()!=null){if(!Set.of("pending","confirmed").contains(query.getReviewState()))throw invalid("核对状态无效");filter+=" and review_state=?";args.add(query.getReviewState());}
        String source=cte(query.getKind());long total=Objects.requireNonNullElse(jdbc.queryForObject(source+"select count(*) from groups"+filter,Long.class,args.toArray()),0L);
        args.add(query.getSize());args.add((query.getPage()-1)*query.getSize());boolean canMaintain=access.canMaintainEngineeringData();
        var rows=jdbc.query(source+"select * from groups"+filter+" order by key"+(byCode(query.getKind())?" desc":"")+" limit ? offset ?",(rs,n)->view(rs,canMaintain),args.toArray());
        return new PageVO<>(rows,query.getPage(),query.getSize(),total,(total+query.getSize()-1)/query.getSize());
    }
    @Transactional public PhoneModelReviewVO confirm(PhoneModelReviewConfirmDTO dto,HttpServletRequest request) {
        var actor=access.requireEngineeringMaintenance();byCode(dto.kind());
        if(dto.key()==null||dto.key().isBlank()||dto.key().length()>256||dto.reason()==null||dto.reason().isBlank()||dto.reason().length()>2000||dto.conclusion()==null||!CONCLUSIONS.contains(dto.conclusion()))throw invalid("请选择单组核对结论并填写依据");
        if(dto.sourceDigest()==null||!dto.sourceDigest().matches("[0-9a-f]{32}"))throw invalid("缺少历史来源摘要，请刷新");
        String groupId=sha256(dto.kind()+":"+dto.key());jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,610141730))",groupId);
        String groupWhere=" where p.deleted_flag=0 and nullif(btrim(p.phone_model_code),'') is not null and nullif(btrim(p.model),'') is not null and "+keySql(dto.kind(),"p")+"=?";
        long hidden=Objects.requireNonNullElse(jdbc.queryForObject("select count(*) from plm_product p"+groupWhere+" and not ("+access.visibleSqlClause("product","p.product_id")+")",Long.class,dto.key()),0L);
        if(hidden>0)throw new BusinessException(40103,"此组包含未授权项目，请由具有完整授权的工程人员核对");
        jdbc.queryForList("select p.product_id from plm_product p"+groupWhere+" order by p.product_id for update",dto.key());
        PhoneModelReviewVO before=group(dto.kind(),dto.key());if(!Objects.equals(before.sourceDigest(),dto.sourceDigest()))throw conflict("历史来源或标准关联已变化，请刷新后重新核对");
        var selected=dto.phoneModelIds()==null?List.<Long>of():dto.phoneModelIds();if(selected.size()>100||selected.stream().anyMatch(id->id==null||id<1)||selected.stream().distinct().count()!=selected.size())throw invalid("核对手机ID无效或重复");phones.members(selected,true);
        var links=dto.projectLinks()==null?List.<PhoneModelReviewConfirmDTO.ProjectLink>of():dto.projectLinks();if(links.size()>100)throw invalid("每次最多关联100个明确单手机项目");var linked=new HashSet<Long>();
        for(var link:links){
            if(link==null||link.projectId()==null||link.phoneModelId()==null||!linked.add(link.projectId()))throw invalid("项目关联ID无效或重复");access.requireProduct(link.projectId());
            var project=before.references().stream().filter(p->p.projectId()==link.projectId()).findFirst().orElseThrow(()->invalid("只能关联本组已展示的项目"));
            if(!"model_variant".equals(project.productType())||!PhoneIdentityMatcher.isSingleModel(project.model()))throw invalid("组合原文或非型号项目不能绑定单一手机；可仅保存历史保留结论");
            if(!Objects.equals(project.phoneModelId(),link.originalPhoneModelId()))throw conflict("项目标准手机已被修改，请刷新核对原值");
            jdbc.queryForList("select id from plm_phone_model_member where id=? for share",link.phoneModelId());var phone=phones.requireEnabledModels(List.of(link.phoneModelId())).get(link.phoneModelId());
            if(phone.get("codeItemId")==null||!Objects.equals(phone.get("phoneModelCode"),project.phoneModelCode().strip()))throw invalid("明确选择的手机与项目原编码不一致；不改写或猜测历史编码");
            jdbc.update("update plm_product set phone_model_member_id=?,updated_by=?,updated_at=now() where product_id=? and phone_model_member_id is not distinct from ?",link.phoneModelId(),actor.username(),link.projectId(),link.originalPhoneModelId());
        }
        var after=group(dto.kind(),dto.key());var details=new LinkedHashMap<String,Object>();details.put("kind",dto.kind());details.put("key",dto.key());details.put("sourceDigest",after.sourceDigest());details.put("originalSourceDigest",before.sourceDigest());details.put("conclusion",dto.conclusion());details.put("reason",dto.reason().strip());details.put("phoneModelIds",selected);details.put("projectLinks",links);details.put("sourceReferences",before.references());
        audit.logRequired(OperationLogCreateCommand.builder().action("CONFIRM_HISTORY").businessType("PHONE_MODEL_REVIEW").businessId(groupId).businessCode(dto.kind()).businessName("手机历史关系核对").detailJson(encode(details)).request(request).build(),actor);
        return group(dto.kind(),dto.key());
    }
    private PhoneModelReviewVO group(String kind,String key) {var rows=jdbc.query(cte(kind)+"select * from groups where key=?",(rs,n)->view(rs,true),kind,key);if(rows.isEmpty())throw conflict("历史差异组已变化或不存在，请刷新");return rows.get(0);}
    private PhoneModelReviewVO view(java.sql.ResultSet rs,boolean canMaintain) throws SQLException {
        try{boolean visible=rs.getBoolean("all_references_visible");return new PhoneModelReviewVO(rs.getString("key"),strings(rs.getArray("code_values")),strings(rs.getArray("model_names")),rs.getLong("source_count"),rs.getString("source_digest"),rs.getString("review_state"),rs.getString("conclusion"),rs.getString("review_reason"),rs.getObject("reviewed_at")==null?null:rs.getObject("reviewed_at").toString(),rs.getString("reviewed_by"),json.readValue(rs.getString("references_json"),new TypeReference<List<PhoneModelReviewVO.Reference>>(){}),json.readValue(rs.getString("review_phone_ids"),new TypeReference<List<Long>>(){}),canMaintain&&visible,!canMaintain?"仅工程职责或管理员可核对":visible?null:"此组包含未授权项目，需要完整授权人员核对");}
        catch(SQLException e){throw e;}catch(Exception e){throw new SQLException("历史关系核对数据解析失败",e);}
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("核对证据序列化失败",e);}}
    private static String sha256(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static List<String> strings(Array value)throws SQLException{if(value==null)return List.of();try{return Arrays.asList((String[])value.getArray());}finally{value.free();}}
    private static BusinessException invalid(String message){return new BusinessException(40001,message);}
    private static BusinessException conflict(String message){return new BusinessException(40901,message);}
}
