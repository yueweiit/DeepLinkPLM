package com.yuewei.plm.module.code.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.security.CurrentUserContext;
import com.yuewei.plm.module.code.dto.PhoneModelReviewConfirmDTO;
import com.yuewei.plm.module.code.dto.PhoneModelReviewQueryDTO;
import com.yuewei.plm.module.code.vo.PhoneModelReviewVO;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceRepository;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceService;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Root runs this opt-in local rollback fixture; no production endpoint or migration is invoked. */
@EnabledIfEnvironmentVariable(named="PLM_COLLAB_TEST_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:15432/plm_data_management\\?currentSchema=plm")
class PhoneModelReviewLocalIntegrationTest {
    JdbcTemplate jdbc;DataSourceTransactionManager tx;TransactionStatus scope;PhoneModelReviewService service;
    OperationLogService audit;CurrentUser admin;long first,second,phone,productLine;String code;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @BeforeEach void begin() {
        var ds=new DriverManagerDataSource(System.getenv("PLM_COLLAB_TEST_DB_URL"),"postgres",System.getenv("PLM_COLLAB_TEST_DB_PASSWORD"));jdbc=new JdbcTemplate(ds);tx=new DataSourceTransactionManager(ds);scope=tx.getTransaction(new DefaultTransactionDefinition());
        var user=jdbc.queryForList("select user_id,username,display_name from sys_user where all_permissions and deleted_flag=0 and status='active' order by user_id limit 1").get(0);admin=new CurrentUser(((Number)user.get("user_id")).longValue(),user.get("username").toString(),user.get("display_name").toString(),true);CurrentUserContext.set(admin);
        audit=mock(OperationLogService.class);when(audit.logRequired(any(),any())).thenAnswer(call->record(call.getArgument(0),call.getArgument(1)));
        var access=new PilotAccessService(jdbc,json);var references=new PhoneModelReferenceService(jdbc,json,new PhoneModelReferenceRepository(jdbc),access,audit);service=new PhoneModelReviewService(jdbc,json,access,references,audit);
        productLine=jdbc.queryForObject("select product_id from plm_product where product_type='product_line' and deleted_flag=0 order by product_id limit 1",Long.class);
        code=jdbc.queryForObject("select lpad(v::text,4,'0') from generate_series(0,9999) v where not exists(select 1 from plm_code_item c where c.code_type='phone_model' and c.code_value=lpad(v::text,4,'0')) and not exists(select 1 from plm_product p where btrim(p.phone_model_code)=lpad(v::text,4,'0')) order by v limit 1",String.class);
        long anchor=jdbc.queryForObject("insert into plm_code_item(code_type,code_value,code_name,status) values('phone_model',?,'Review source','enabled') returning code_item_id",Long.class,code);
        long brand=jdbc.queryForObject("insert into plm_phone_brand(name,created_by,updated_by) values(?,'it','it') returning id",Long.class,"Review-brand-"+UUID.randomUUID());
        phone=jdbc.queryForObject("insert into plm_phone_model_member(brand_id,model_name,code_item_id,created_by,updated_by) values(?,'Confirmed standard',?,'it','it') returning id",Long.class,brand,anchor);
        first=project("Original alias one");second=project("Original alias two");
    }
    @AfterEach void rollback(){if(scope!=null&&!scope.isCompleted())tx.rollback(scope);CurrentUserContext.clear();}
    long project(String name){return jdbc.queryForObject("insert into plm_product(product_id,product_code,product_name,product_type,parent_product_id,model,phone_model_code,status,version_no,created_by,updated_by) values(?,?,?,'model_variant',?,?,?,'draft','A','it','it') returning product_id",Long.class,UUID.randomUUID().getMostSignificantBits()&Long.MAX_VALUE,"REVIEW-"+UUID.randomUUID(),name,productLine,name,code);}
    Long record(OperationLogCreateCommand command,CurrentUser actor) {
        var columns=new ArrayList<>(List.of("request_id","operator_user_name","business_type","business_id","business_code","action","detail_json","result","created_by","updated_by"));
        var values=new ArrayList<Object>(List.of(UUID.randomUUID().toString(),actor.displayName(),command.getBusinessType(),command.getBusinessId(),command.getBusinessCode(),command.getAction(),command.getDetailJson(),"success",actor.username(),actor.username()));
        if(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=current_schema() and table_name='plm_operation_log' and column_name='operation_log_id'",Long.class)>0){columns.add("operation_log_id");values.add(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());}
        return jdbc.queryForObject("insert into plm_operation_log("+String.join(",",columns)+") values("+String.join(",",Collections.nCopies(values.size(),"?"))+") returning log_id",Long.class,values.toArray());
    }
    PhoneModelReviewQueryDTO query(){var q=new PhoneModelReviewQueryDTO();q.setKeyword(code);return q;}
    PhoneModelReviewVO group(){return service.page(query()).getContent().stream().filter(r->r.key().equals(code)).findFirst().orElseThrow();}
    PhoneModelReviewConfirmDTO confirmation(String conclusion,List<PhoneModelReviewConfirmDTO.ProjectLink> links){var row=group();return new PhoneModelReviewConfirmDTO("code_many_names",code,row.sourceDigest(),conclusion,"逐条核对原始历史凭证，原文和编码保留",List.of(phone),links);}
    @Test void confirmingOneHistoryGroupKeepsOriginalTextAndBecomesPendingWhenItsSourceChanges() {
        var before=group();assertThat(before.reviewState()).isEqualTo("pending");assertThat(before.references()).extracting(PhoneModelReviewVO.Reference::projectId).containsExactlyInAnyOrder(first,second);
        var confirmed=service.confirm(confirmation("same_phone_alias",List.of()),null);assertThat(confirmed.reviewState()).isEqualTo("confirmed");assertThat(confirmed.reviewReason()).contains("历史凭证");assertThat(confirmed.reviewPhoneModelIds()).containsExactly(phone);
        assertThat(jdbc.queryForObject("select model from plm_product where product_id=?",String.class,first)).isEqualTo("Original alias one");
        var filter=query();filter.setReviewState("confirmed");assertThat(service.page(filter).getContent()).hasSize(1);
        jdbc.update("update plm_product set model='Original alias one changed' where product_id=?",first);assertThat(group().reviewState()).isEqualTo("pending");assertThat(service.page(filter).getContent()).isEmpty();
    }
    @Test void aSinglePhoneProjectCanBeLinkedWithOriginalValueWithoutChangingItsSnapshot() {
        var confirmed=service.confirm(confirmation("same_phone_alias",List.of(new PhoneModelReviewConfirmDTO.ProjectLink(first,null,phone))),null);
        assertThat(confirmed.reviewState()).isEqualTo("confirmed");assertThat(confirmed.references().stream().filter(r->r.projectId()==first).findFirst().orElseThrow().phoneModelId()).isEqualTo(phone);
        assertThat(jdbc.queryForMap("select model,phone_model_code,phone_model_member_id from plm_product where product_id=?",first)).containsEntry("model","Original alias one").containsEntry("phone_model_code",code).containsEntry("phone_model_member_id",phone);
    }
    @Test void combinedOriginalCanBeReviewedWithoutBindingItToOnePhone() {
        jdbc.update("update plm_product set model='Phone one / Phone two' where product_id=?",first);
        assertThat(service.confirm(confirmation("combined_original_preserved",List.of()),null).reviewState()).isEqualTo("confirmed");
        assertThat(jdbc.queryForObject("select phone_model_member_id from plm_product where product_id=?",Long.class,first)).isNull();
        assertThatThrownBy(()->service.confirm(confirmation("combined_original_preserved",List.of(new PhoneModelReviewConfirmDTO.ProjectLink(first,null,phone))),null)).hasMessageContaining("组合原文");
    }
    @Test void staleSourcesAndStaleProjectOriginalsRejectBeforeWritingAnyDecision() {
        var stale=confirmation("same_phone_alias",List.of());jdbc.update("update plm_product set model='Changed source' where product_id=?",first);
        assertThatThrownBy(()->service.confirm(stale,null)).hasMessageContaining("历史来源或标准关联已变化");
        jdbc.update("update plm_product set phone_model_member_id=? where product_id=?",phone,first);
        assertThatThrownBy(()->service.confirm(confirmation("same_phone_alias",List.of(new PhoneModelReviewConfirmDTO.ProjectLink(first,null,phone))),null)).hasMessageContaining("项目标准手机已被修改");
        assertThat(group().reviewState()).isEqualTo("pending");
    }
    @Test void auditFailureRollsBackAProjectLinkTogetherWithTheHistoryDecision() {
        var input=confirmation("same_phone_alias",List.of(new PhoneModelReviewConfirmDTO.ProjectLink(first,null,phone)));doThrow(new IllegalStateException("required audit unavailable")).when(audit).logRequired(any(),any());
        var nested=new TransactionTemplate(tx);nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        assertThatThrownBy(()->nested.execute(status->service.confirm(input,null))).hasMessageContaining("required audit unavailable");
        assertThat(jdbc.queryForObject("select phone_model_member_id from plm_product where product_id=?",Long.class,first)).isNull();assertThat(group().reviewState()).isEqualTo("pending");
    }
    @Test void partiallyAuthorizedGroupCannotExposeOrConfirmItsHiddenReferences() {
        long hidden=project("Hidden historical alias");long uid=jdbc.queryForObject("select max(user_id)+100 from sys_user",Long.class);
        jdbc.update("insert into sys_user(user_id,user_code,user_name,login_name,username,display_name,status,all_permissions) values(?,?,?,?,?,?,'active',false)",uid,"review-"+uid,"Review engineer","review-"+uid,"review-"+uid,"Review engineer");
        jdbc.update("insert into plm_pilot_account(user_id,role_code,permissions,all_objects,created_by,updated_by) values(?,'domestic','[\"collaboration:view\"]'::jsonb,false,'it','it')",uid);jdbc.update("insert into plm_collaboration_assignment(field_group,user_id) values('engineering',?)",uid);
        for(long id:List.of(first,second))jdbc.update("insert into plm_pilot_scope(user_id,entity_type,entity_id) values(?,'product',?)",uid,id);
        CurrentUserContext.set(new CurrentUser(uid,"forged-admin","forged-admin",true));var limited=group();assertThat(limited.canReview()).isFalse();assertThat(limited.references()).extracting(PhoneModelReviewVO.Reference::projectId).doesNotContain(hidden);
        assertThatThrownBy(()->service.confirm(new PhoneModelReviewConfirmDTO("code_many_names",code,limited.sourceDigest(),"same_phone_alias","核对依据",null,null),null)).hasMessageContaining("未授权项目");
    }
}
