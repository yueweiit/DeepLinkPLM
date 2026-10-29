package com.yuewei.plm.module.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.vo.PageVO;
import com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceService;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceRepository;
import com.yuewei.plm.module.moldreference.PhoneIdentityMatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import static com.yuewei.plm.module.collaboration.CollaborationTypes.*;
import static com.yuewei.plm.module.collaboration.CollaborationFields.*;

@Service
public class CollaborationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CollaborationRepository repository;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;
    private final OperationLogService operationLog;
    private final PilotAccessService access;
    private final PhoneModelReferenceService phones;
    private final MoldMasterRepository masters;
    private final MoldWorkflowRepository workflow;
    static final Set<String> MASTER_FIELDS=Set.of("moldName","engravedCode","engravedCodeConfirmed","brandId","compatiblePhones");
    static final Set<String> LEDGER_FIELDS=Set.of("setName","manufacturerName","ownerUserId","notes","pcMoldCodeId","tpuMoldCodeId","pcOriginalMoldCode","tpuOriginalMoldCode","pcInventoryId","tpuInventoryId","pcCurrentLocation","tpuCurrentLocation","pcLocationDetail","tpuLocationDetail","pcPhysicalStatus","tpuPhysicalStatus","quoteAmount","quoteCurrency","quoteBasis","quoteConfirmed");

    @Autowired
    public CollaborationService(JdbcTemplate jdbc,ObjectMapper json,CollaborationRepository repository,
                                org.springframework.transaction.PlatformTransactionManager transactionManager,
                                OperationLogService operationLog,PilotAccessService access,PhoneModelReferenceService phones) {
        this.jdbc=jdbc;this.json=json;this.repository=repository;this.transactionManager=transactionManager;
        this.operationLog=operationLog;this.access=access;this.phones=phones;this.masters=new MoldMasterRepository(jdbc,repository);this.workflow=new MoldWorkflowRepository(jdbc);
    }
    public CollaborationService(JdbcTemplate jdbc,ObjectMapper json,CollaborationRepository repository,
                                org.springframework.transaction.PlatformTransactionManager transactionManager,OperationLogService operationLog,PilotAccessService access) {
        this(jdbc,json,repository,transactionManager,operationLog,access,new PhoneModelReferenceService(jdbc,json,new PhoneModelReferenceRepository(jdbc),access,operationLog));
    }
    public CollaborationService(JdbcTemplate jdbc,ObjectMapper json,CollaborationRepository repository,
                                org.springframework.transaction.PlatformTransactionManager transactionManager,OperationLogService operationLog) {
        this(jdbc,json,repository,transactionManager,operationLog,new PilotAccessService(jdbc,json));
    }
    private void read() {access.requireDomesticView();access.requirePermission("collaboration:view");}
    private String scope(Query query) {return query.getView().equals("delivery")?access.visibleSqlClause("mold","s.mold_set_id"):access.visibleSqlClause("item","i.item_id");}
    private void requireRecord(String entity,long id) {if(entity.equals("item")) access.requireItem(id);else access.requireMold(id);}

    CurrentUser actor() {
        return com.yuewei.plm.common.security.CurrentUserResolver.resolve(jdbc);
    }
    Set<String> groups(CurrentUser actor) {
        if(actor.allPermissions()) return new LinkedHashSet<>(GROUPS);
        return new LinkedHashSet<>(jdbc.queryForList("select field_group from plm_collaboration_assignment where user_id=?",String.class,actor.userId()));
    }
    void requireGroup(CurrentUser actor,String group) {
        if(!groups(actor).contains(group)) throw new BusinessException(40103,"没有该字段组的填写权限："+group);
    }
    private void admin(CurrentUser actor) {if(!actor.allPermissions()) throw new BusinessException(40103,"仅管理员可以配置协作职责");}
    public PageVO<Map<String,Object>> page(Query query) {read();if(query.getProjectId()!=null)access.requireProduct(query.getProjectId());if(!Set.of("market","delivery","production").contains(query.getView())) throw invalid("请选择协作资料视图");query.setMoldLedger(query.getView().equals("delivery"));var page=repository.page(query,groups(actor()),scope(query));protectQuote(page.getContent());return page;}
    public PageVO<Map<String,Object>> moldPage(Query query) {
        read();if(query.getProjectId()!=null)access.requireProduct(query.getProjectId());if(query.getView().equals("master")) {
            var page=masters.page(query,access.visibleSqlClause("item","i.item_id"),access.visibleSqlClause("mold","s.mold_set_id"));
            protectQuote(page.getContent());return page;
        }
        query.setView("delivery");query.setMoldLedger(true);var page=repository.page(query,groups(actor()),scope(query));
        workflow.enrich(page.getContent());LocalDate today=MoldSchedulePolicy.today();page.getContent().forEach(row->MoldSchedulePolicy.enrichTasks(row,today));protectQuote(page.getContent());return page;
    }
    public Map<String,Object> mold(long id) {read();access.requireMold(id);var row=repository.row("mold_set",id);workflow.enrich(List.of(row));MoldSchedulePolicy.enrichTasks(row,MoldSchedulePolicy.today());protectQuote(row);return row;}
    public Map<String,Object> moldOptions() {
        var result=new LinkedHashMap<>(options());result.put("masterFields",ITEM.stream().filter(f->MASTER_FIELDS.contains(f.key())).toList());
        result.put("moldFields",MOLD.stream().filter(f->LEDGER_FIELDS.contains(f.key())).toList());
        result.put("canReadQuote",access.hasPermission("finance:read"));result.put("canWriteQuote",access.hasPermission("finance:write"));
        result.put("canConfirmQuote",access.hasPermission("finance:confirm"));result.put("canExportQuote",access.hasPermission("finance:export"));return result;
    }
    public List<Map<String,Object>> inventoryOptions(String keyword) {read();return repository.moldInventoryOptions(keyword);}
    public PageVO<Map<String,Object>> moldHistory(long id,int page,int size) {read();access.requireMold(id);return protectHistory(repository.moldHistory(id,page,size));}
    public Map<String,Object> moldReferences(long id) {Map<String,Object> mold=mold(id);return references(((Number)mold.get("itemId")).longValue());}
    public Map<String,Object> copyMold(long id,CreateMoldSet dto,HttpServletRequest request) {
        Map<String,Object> source=mold(id);
        return createMoldSet(((Number)source.get("itemId")).longValue(),new CreateMoldSet(dto.setName(),null,null,id,dto.existingPhysical(),dto.reason()),request);
    }
    public Map<String,Object> master(long id,int page,int size) {
        read();access.requireItem(id);
        var master=masters.detail(id,access.visibleSqlClause("item","i.item_id"),access.visibleSqlClause("mold","s.mold_set_id"));protectQuote(master);
        Query q=new Query();q.setItemId(id);q.setPage(page);q.setSize(size);var sets=moldPage(q);
        return Map.of("master",master,"sets",sets,"members",master.get("members"),"summary",master.get("summary"),"setOptions",masters.setOptions(id,access.visibleSqlClause("mold","s.mold_set_id")));
    }
    public Map<String,Object> createMaster(CreateMaster dto,HttpServletRequest request) {
        read();CurrentUser actor=actor();requireGroup(actor,"engineering");
        return new TransactionTemplate(transactionManager).execute(tx->{
            if(dto.brandId()!=null) phones.validateBrand(dto.brandId());
            CreateItem item=new CreateItem(dto.productLineId(),dto.styleName(),null,Objects.toString(dto.compatibleModels(),dto.moldName()),null,null);
            Map<String,Object> created=createArchive(item,request,false);long id=((Number)created.get("itemId")).longValue();
            List<CellChange> changes=new ArrayList<>();changes.add(new CellChange("moldName",json.nullNode(),json.valueToTree(dto.moldName()),"新增模具档案"));
            if(dto.engravedCode()!=null) changes.add(new CellChange("engravedCode",json.nullNode(),json.valueToTree(dto.engravedCode()),"保留原实物刻码，待确认"));
            if(dto.brandId()!=null) changes.add(new CellChange("brandId",json.nullNode(),json.valueToTree(dto.brandId()),"明确选择品牌资料"));
            var result=saveRow(actor,groups(actor),new RowPatch("item",id,changes),request);
            if(!result.status().equals("saved")) throw invalid(result.message());return result.row();
        });
    }
    public List<RowResult> saveMoldLedger(BatchSave dto,HttpServletRequest request) {
        read();for(RowPatch row:dto.rows()) {
            Set<String> fields=row.entityType().equals("item")?MASTER_FIELDS:LEDGER_FIELDS;
            if(row.changes().stream().anyMatch(c->!fields.contains(c.field()))) throw invalid("模具台账不填写排程、验收或市场字段，请使用对应入口");
        }
        return save(dto,request);
    }
    public Map<String,Object> options() {
        read();CurrentUser actor=actor();Set<String> allowed=groups(actor);
        return Map.of("productLines",repository.productLines(access.visibleSqlClause("product","p.product_id")),"itemFields",ITEM,"moldFields",MOLD,
            "writableGroups",allowed,"administrator",actor.allPermissions(),"userId",actor.userId(),
            "users",repository.users().stream().map(u->Map.of("userId",u.get("userId"),"username",u.get("username"),"displayName",Objects.toString(u.get("displayName"),u.get("username").toString()))).toList());
    }
    public Map<String,Object> item(long id) {read();access.requireItem(id);return repository.row("item",id);}
    public Map<String,Object> references(long id) {read();access.requireItem(id);var result=repository.references(id);protectQuote(result);return result;}
    public PageVO<Map<String,Object>> history(long id,int page,int size) {read();access.requireItem(id);return protectHistory(repository.history(id,page,size));}
    public List<Map<String,Object>> projectOptions(Long line,String keyword) {read();return repository.projectOptions(line,keyword,access.visibleSqlClause("product","p.product_id"));}
    public List<Map<String,Object>> moldCodes(Long item,String keyword) {read();if(item!=null) access.requireItem(item);return repository.moldCodes(item,keyword,item==null?access.visibleSqlClause("product","mc.product_id"):"true");}

    public Map<String,Object> createItem(CreateItem dto,HttpServletRequest request) {
        return createArchive(dto,request,true);
    }
    private Map<String,Object> createArchive(CreateItem dto,HttpServletRequest request,boolean market) {
        read();CurrentUser actor=actor();if(market) requireGroup(actor,"market");
        return new TransactionTemplate(transactionManager).execute(tx-> {
            if(dto.brandId()!=null) phones.validateBrand(dto.brandId());
            String style=Objects.toString(normalise(field("item","styleName"),json.valueToTree(dto.styleName())),"");
            String model=Objects.toString(normalise(field("item","compatibleModels"),json.valueToTree(dto.compatibleModels())),"");
            Object code=normalise(field("item","phoneModelCode"),json.valueToTree(dto.phoneModelCode()));
            Object brand=normalise(field("item","brand"),json.valueToTree(dto.brand()));
            Object phonePrice=normalise(field("item","phonePrice"),json.valueToTree(dto.phonePrice()));
            Object priceCurrency=normalise(field("item","priceCurrency"),json.valueToTree(dto.priceCurrency()));
            Object salesPlatform=normalise(field("item","salesPlatform"),json.valueToTree(dto.salesPlatform()));
            Object referenceUrl=normalise(field("item","referenceUrl"),json.valueToTree(dto.referenceUrl()));
            Object notes=normalise(field("item","notes"),json.valueToTree(dto.notes()));
            if(brand==null) brand=brandFromModel(model);
            validateLine(dto.productLineId());requireNewLine(dto.productLineId());
            Long phoneId=dto.phoneModelCodeItemId()!=null?dto.phoneModelCodeItemId():resolveLegacyPhoneCode(code,model);
            String linkedCode=validatePhoneLink(phoneId,true);
            requireCodeBrand(dto.brandId(),phoneId);
            if(linkedCode!=null) {requireCodeSnapshot(code,linkedCode);if(code==null) code=linkedCode;}
            requireIdentity(null,dto.productLineId(),phoneId,style,model);
            Long id=jdbc.queryForObject("""
                insert into plm_collaboration_item(product_line_id,style_name,brand,compatible_models,phone_model_code,phone_model_code_item_id,brand_id,
                  phone_price,price_currency,sales_platform,reference_url,notes,created_by,updated_by)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?) returning item_id
                """,Long.class,dto.productLineId(),style,brand,model,code,phoneId,dto.brandId(),phonePrice,priceCurrency,
                salesPlatform,referenceUrl,notes,actor.username(),actor.username());
            Map<String,Object> row=repository.row("item",id);
            history(actor,id,"item",id,"__created",null,row,"新增协作档案");
            log(actor,id,"COLLABORATION_CREATE",row,request);return row;
        });
    }
    public Map<String,Object> createMoldSet(long itemId,CreateMoldSet dto,HttpServletRequest request) {
        read();CurrentUser actor=actor();requireGroup(actor,"engineering");access.requireItem(itemId);
        if(!dto.existingPhysical())throw invalid("新制作或重新开模须先发起PLM开模申请；此入口只登记已有实物");
        if(dto.reason()==null||dto.reason().isBlank())throw invalid("登记已有实物须填写清点、历史凭证或其他事实依据");
        return registerSet(itemId,dto,request,actor,"已有实物登记依据："+dto.reason().strip());
    }
    /** Existing reviewed historical import is the sole internal compatibility caller. */
    Map<String,Object> importMoldSet(long itemId,CreateMoldSet dto,HttpServletRequest request) {
        read();CurrentUser actor=actor();requireGroup(actor,"engineering");access.requireItem(itemId);
        return registerSet(itemId,dto,request,actor,"历史导入登记；原始来源及核对依据见导入批次");
    }
    private Map<String,Object> registerSet(long itemId,CreateMoldSet dto,HttpServletRequest request,CurrentUser actor,String registrationBasis) {
        return new TransactionTemplate(transactionManager).execute(tx-> {
            if(dto.sourceMoldSetId()!=null) {
                access.requireMold(dto.sourceMoldSetId());
                workflow.lockExecution(dto.sourceMoldSetId());
                // This path records already existing facts, including facts predating an execution pause.
            }
            lock("item",itemId);
            com.yuewei.plm.module.masterdata.ProductMoldLifecycleService.requireActive(jdbc,"item",itemId);
            if(dto.sourceMoldSetId()!=null)com.yuewei.plm.module.masterdata.ProductMoldLifecycleService.requireActive(jdbc,"mold_set",dto.sourceMoldSetId());
            Map<String,Object> source=null;
            Long pcCode=dto.pcMoldCodeId(),tpuCode=dto.tpuMoldCodeId();
            if(dto.sourceMoldSetId()!=null) {
                access.requireMold(dto.sourceMoldSetId());
                lock("mold_set",dto.sourceMoldSetId());source=repository.row("mold_set",dto.sourceMoldSetId());
                if(!same(source.get("itemId"),itemId)) throw invalid("复制来源必须属于同一款式与兼容型号档案");
                pcCode=(Long)source.get("pcMoldCodeId");tpuCode=(Long)source.get("tpuMoldCodeId");
            }
            validateMoldCode(itemId,pcCode);validateMoldCode(itemId,tpuCode);
            Integer setNo=jdbc.queryForObject("select coalesce(max(set_no),0)+1 from plm_collaboration_mold_set where item_id=?",Integer.class,itemId);
            String name=dto.setName()==null||dto.setName().isBlank()?"第 "+setNo+" 套（已有资料登记）":dto.setName().trim();
            normalise(field("mold_set","setName"),json.valueToTree(name));
            Long id=jdbc.queryForObject("insert into plm_collaboration_mold_set(item_id,set_no,set_name,source_mold_set_id,notes,created_by,updated_by) values(?,?,?,?,?,?,?) returning mold_set_id",Long.class,itemId,setNo,name,dto.sourceMoldSetId(),registrationBasis,actor.username(),actor.username());
            for(String part:List.of("PC","TPU")) {
                String prefix=part.equals("PC")?"pc":"tpu";
                jdbc.update("insert into plm_collaboration_mold_part(mold_set_id,part_type,product_mold_code_id,original_mold_code,inventory_id) values(?,?,?,?,?)",id,part,part.equals("PC")?pcCode:tpuCode,source==null?null:source.get(prefix+"OriginalMoldCode"),source==null?null:source.get(prefix+"InventoryId"));
            }
            Map<String,Object> row=repository.row("mold_set",id);
            history(actor,itemId,"mold_set",id,"__created",null,row,registrationBasis+"；部件实物、适配及验收须独立核对");
            log(actor,itemId,"COLLABORATION_MOLD_CREATE",row,request);return row;
        });
    }
    public List<RowResult> save(BatchSave dto,HttpServletRequest request) {
        CurrentUser actor=actor();Set<String> allowed=groups(actor);List<RowResult> results=new ArrayList<>();
        for(RowPatch row:dto.rows()) {
            try {results.add(new TransactionTemplate(transactionManager).execute(tx->saveRow(actor,allowed,row,request)));}
            catch(BusinessException e) {results.add(new RowResult(row.entityType(),row.id(),e.getCode()==40103?"forbidden":"invalid",e.getMessage(),List.of(),null));}
            catch(org.springframework.dao.DataIntegrityViolationException e) {results.add(new RowResult(row.entityType(),row.id(),"invalid","关联或数据约束冲突，请刷新核对",List.of(),null));}
        }
        workflow.enrich(results.stream().filter(r->r.row()!=null&&r.entityType().equals("mold_set")).map(RowResult::row).toList());
        LocalDate today=MoldSchedulePolicy.today();
        results.stream().filter(r->r.row()!=null).forEach(r->{if(r.entityType().equals("mold_set")) MoldSchedulePolicy.enrichTasks(r.row(),today);});
        protectQuote(results.stream().filter(r->r.row()!=null).map(RowResult::row).toList());
        protectQuote(results.stream().map(RowResult::conflicts).toList());
        return results;
    }
    private RowResult saveRow(CurrentUser actor,Set<String> allowed,RowPatch patch,HttpServletRequest request) {
        requireRecord(patch.entityType(),patch.id());
        if(patch.entityType().equals("mold_set")) workflow.lockExecution(patch.id());
        lock(patch.entityType(),patch.id());Map<String,Object> current=repository.row(patch.entityType(),patch.id());
        if(patch.entityType().equals("item")&&patch.changes().stream().anyMatch(c->c.field().equals("compatiblePhones")))
            for(Number set:(List<Number>)current.get("compatibilityMoldSetIds")) access.requireMold(set.longValue());
        long itemId=((Number)current.get("itemId")).longValue();
        Map<String,Object> next=new LinkedHashMap<>(current);List<Map<String,Object>> conflicts=new ArrayList<>();
        Set<String> seen=new HashSet<>();String reason="";
        for(CellChange change:patch.changes()) {
            Field f=field(patch.entityType(),change.field());
            if(!seen.add(f.key())) throw invalid("同一字段不能重复提交");
            if(f.group().equals("finance")) {if(f.key().equals("quoteConfirmed")) access.requireFinanceConfirm();else access.requireFinanceWrite();}
            else if(!allowed.contains(f.group())&&!(f.key().equals("brandId")&&allowed.contains("market"))) throw new BusinessException(40103,"没有填写权限："+f.label());
            Object before=normalise(f,change.before());Object after=normalise(f,change.after());
            Object stored=f.kind().equals("compatibilities")?normalise(f,json.valueToTree(current.get(f.key()))):current.get(f.key());
            if(!same(stored,before)) {
                Map<String,Object> conflict=new LinkedHashMap<>();conflict.put("field",f.key());conflict.put("label",f.label());
                conflict.put("before",before);conflict.put("current",current.get(f.key()));conflict.put("attempted",after);conflicts.add(conflict);
            }
            if((f.kind().equals("part_status")||f.kind().equals("acceptance"))&&after==null) throw invalid(f.label()+"不能为空");
            if(change.reason()!=null&&!change.reason().isBlank()) reason=change.reason().trim();
            next.put(f.key(),after);
        }
        for(var dependency:patch.expectedValues().entrySet()) {
            Field f=expectedField(patch.entityType(),dependency.getKey());Object expected=normalise(f,dependency.getValue());
            if(!same(current.get(f.key()),expected)&&conflicts.stream().noneMatch(c->c.get("field").equals(f.key()))) {
                Map<String,Object> conflict=new LinkedHashMap<>();conflict.put("field",f.key());conflict.put("label",f.label());
                conflict.put("before",expected);conflict.put("current",current.get(f.key()));conflict.put("attempted",expected);conflict.put("expectedOnly",true);conflicts.add(conflict);
            }
        }
        if(!conflicts.isEmpty()) return new RowResult(patch.entityType(),patch.id(),"conflict","此行已被他人修改，请核对后重新确认",conflicts,current);
        for(CellChange change:patch.changes()) {
            Field f=field(patch.entityType(),change.field());Object after=next.get(f.key());
            if(f.kind().equals("count") && current.get(f.key())!=null &&
                (after==null || ((Number)after).longValue()<((Number)current.get(f.key())).longValue()) &&
                (change.reason()==null||change.reason().isBlank())) throw invalid("下调或清空累计产量须填写更正原因");
        }
        if(patch.entityType().equals("item")) {
            List<CellChange> masterDerived=validateMaster(actor,current,next,seen,patch);
            if(!masterDerived.isEmpty()) {List<CellChange> changes=new ArrayList<>(patch.changes());changes.addAll(masterDerived);patch=new RowPatch(patch.entityType(),patch.id(),changes,patch.expectedValues());}
            validateLine((Long)next.get("productLineId"));
            if(!same(current.get("productLineId"),next.get("productLineId"))) requireNewLine((Long)next.get("productLineId"));
            List<CellChange> derived=alignPhoneLink(current,next,seen);
            if(!derived.isEmpty()) {
                List<CellChange> changes=new ArrayList<>(patch.changes());changes.addAll(derived);
                patch=new RowPatch(patch.entityType(),patch.id(),List.copyOf(changes),patch.expectedValues());
            }
            if(next.get("moldName")==null) requireIdentity(patch.id(),(Long)next.get("productLineId"),(Long)next.get("phoneModelCodeItemId"),next.get("styleName").toString(),next.get("compatibleModels").toString());
            if(((Number)current.get("projectCount")).longValue()>0 &&
                (!same(current.get("productLineId"),next.get("productLineId"))||!same(current.get("phoneModelCode"),next.get("phoneModelCode"))||!same(current.get("phoneModelCodeItemId"),next.get("phoneModelCodeItemId"))))
                throw invalid("已关联项目的产品线和型号编码不能直接改动，请先核对项目关联");
            if(next.get("phonePrice")!=null && next.get("priceCurrency")==null) throw invalid("填写价格时请选择币种，无法确定请选“待确认”");
        } else {
            List<CellChange> quoteDerived=validateQuote(current,next,seen,patch);
            if(!quoteDerived.isEmpty()) {List<CellChange> changes=new ArrayList<>(patch.changes());changes.addAll(quoteDerived);patch=new RowPatch(patch.entityType(),patch.id(),changes,patch.expectedValues());}
            List<String> warnings=patch.changes().stream().anyMatch(c->field("mold_set",c.field()).kind().equals("date"))?dateWarnings(next):List.of();
            if(!warnings.isEmpty()&&reason.isBlank()) throw invalid(String.join("；",warnings)+"。请修正日期或填写核对说明");
            validateMoldCode(itemId,(Long)next.get("pcMoldCodeId"));validateMoldCode(itemId,(Long)next.get("tpuMoldCodeId"));
            if(seen.contains("ownerUserId")&&!same(current.get("ownerUserId"),next.get("ownerUserId"))&&next.get("ownerUserId")!=null) {
                if(jdbc.queryForObject("select count(*) from sys_user where user_id=? and deleted_flag=0 and status='active'",Long.class,next.get("ownerUserId"))==0) throw invalid("请选择有效账号作为模具负责人");
            }
            if(workflow.isPaused(patch.id())) MoldExecutionPolicy.validate(current,next,patch.changes(),MoldSchedulePolicy.today());
            validateParts(actor,current,next,patch.changes());
            MoldScheduleTypes.validate(current,next,patch.changes());
            for(String key:List.of("logisticsOwnerUserId","mexicoOwnerUserId","productionOwnerUserId")) {
                if(seen.contains(key)&&next.get(key)!=null&&jdbc.queryForObject("select count(*) from sys_user where user_id=? and deleted_flag=0 and status='active'",Long.class,next.get(key))==0) throw invalid("请选择有效账号作为排程负责人");
            }
        }
        // A part is updated atomically: acceptance constraints must see the final dates/result,
        // regardless of the order of fields in a clipboard or import payload.
        if(patch.entityType().equals("mold_set")) for(String prefix:List.of("pc","tpu")) {
            List<Field> changed=patch.changes().stream().map(c->field("mold_set",c.field()))
                .filter(f->partField(f)&&f.key().startsWith(prefix)&&!same(current.get(f.key()),next.get(f.key()))).toList();
            if(!changed.isEmpty()) {
                List<Object> args=new ArrayList<>();changed.forEach(f->args.add(sqlValue(next.get(f.key()))));args.add(patch.id());args.add(prefix.toUpperCase(Locale.ROOT));
                jdbc.update("update plm_collaboration_mold_part set "+String.join(",",changed.stream().map(f->f.column()+"=?").toList())+" where mold_set_id=? and part_type=?",args.toArray());
            }
        }
        for(CellChange change:patch.changes()) {
            Field f=field(patch.entityType(),change.field());Object value=next.get(f.key());
            if(same(current.get(f.key()),value)) continue;
            if(!(patch.entityType().equals("mold_set")&&partField(f))&&!f.key().equals("compatiblePhones")) jdbc.update("update "+table(patch.entityType())+" set "+f.column()+"=? where "+idColumn(patch.entityType())+"=?",sqlValue(value),patch.id());
            history(actor,itemId,patch.entityType(),patch.id(),f.key(),current.get(f.key()),value,change.reason());
        }
        if(patch.entityType().equals("item")&&seen.contains("compatiblePhones")) saveMembers(actor,itemId,current,next);
        // Explicit arrival-plan confirmation captures the basis even when the entered date is unchanged.
        if(patch.entityType().equals("mold_set")&&seen.contains("plannedFactoryArrivalDate")) {
            Object basis=next.get("plannedFactoryArrivalDate")==null?null:next.get("plannedMoldDeliveryDate");
            jdbc.update("update plm_collaboration_mold_set set arrival_confirmed_basis_date=? where mold_set_id=?",basis==null?null:java.sql.Date.valueOf(parseDate(basis.toString())),patch.id());
            if(!same(current.get("arrivalConfirmedBasisDate"),basis)) history(actor,itemId,"mold_set",patch.id(),"arrivalConfirmedBasisDate",current.get("arrivalConfirmedBasisDate"),basis,"负责人确认到厂计划");
        }
        jdbc.update("update "+table(patch.entityType())+" set master_data_version=master_data_version+1,updated_at=now(),updated_by=? where "+idColumn(patch.entityType())+"=?",actor.username(),patch.id());
        log(actor,itemId,"COLLABORATION_UPDATE",patch,request);
        return new RowResult(patch.entityType(),patch.id(),"saved","已保存",List.of(),repository.row(patch.entityType(),patch.id()));
    }
    @SuppressWarnings("unchecked")
    private List<CellChange> validateMaster(CurrentUser actor,Map<String,Object> current,Map<String,Object> next,Set<String> seen,RowPatch patch) {
        List<CellChange> derived=new ArrayList<>();
        if(seen.contains("brandId")&&!same(current.get("brandId"),next.get("brandId"))&&next.get("brandId")!=null) phones.validateBrand((Long)next.get("brandId"));
        if(Boolean.TRUE.equals(current.get("engravedCodeConfirmed"))&&(!same(current.get("engravedCode"),next.get("engravedCode"))||!Boolean.TRUE.equals(next.get("engravedCodeConfirmed")))) {
            if(!actor.allPermissions()) throw new BusinessException(40103,"已确认实物刻码锁定，仅管理员可更正录入错误");
            if(patch.changes().stream().filter(c->Set.of("engravedCode","engravedCodeConfirmed").contains(c.field())).anyMatch(c->c.reason()==null||c.reason().isBlank())) throw invalid("更正已确认原刻码须填写录入错误原因");
        }
        if(next.get("engravedCodeConfirmed")==null) throw invalid("原刻码确认状态不能为空");
        if(Boolean.TRUE.equals(next.get("engravedCodeConfirmed"))&&next.get("engravedCode")==null) throw invalid("确认实物刻码前须填写原刻码");
        if(!seen.contains("compatiblePhones")) {
            if(seen.contains("brandId")&&next.get("brandId")!=null) requireMemberBrand(next.get("brandId"),phones.members(((List<Number>)current.get("compatiblePhoneModelIds")).stream().map(Number::longValue).toList(),true));
            if(seen.contains("brandId")&&((List<?>)current.get("compatiblePhoneModelIds")).isEmpty())requireCodeBrand((Long)next.get("brandId"),(Long)next.get("phoneModelCodeItemId"));
            return derived;
        }
        List<Map<String,Object>> relations=(List<Map<String,Object>>)next.get("compatiblePhones");
        List<Long> ids=relations.stream().map(r->((Number)r.get("phoneModelId")).longValue()).toList();
        if(ids.isEmpty()) return derived;
        Map<Long,Map<String,Object>> original=relationMap(current.get("compatiblePhones"));List<Long> requireActive=new ArrayList<>();Set<Long> setIds=new TreeSet<>();
        for(var relation:relations) {
            long phone=((Number)relation.get("phoneModelId")).longValue();List<Long> applicable=(List<Long>)relation.get("setIds");
            var prior=original.get(phone);List<Long> priorSets=prior==null?List.of():(List<Long>)prior.get("setIds");
            if(prior==null||applicable.stream().anyMatch(id->!priorSets.contains(id))) requireActive.add(phone);
            setIds.addAll(applicable);
        }
        if(!requireActive.isEmpty())com.yuewei.plm.module.masterdata.ProductMoldLifecycleService.requireActive(jdbc,"item",((Number)current.get("itemId")).longValue());
        var memberRows=phones.members(ids,true);phones.requireEnabledModels(requireActive);
        requireMemberBrand(next.get("brandId"),memberRows);
        long itemId=((Number)current.get("itemId")).longValue();
        for(long setId:setIds) {access.requireMold(setId);lock("mold_set",setId);if(!same(repository.row("mold_set",setId).get("itemId"),itemId)) throw invalid("兼容确认的实物套必须属于本模具档案");}
        String display=phones.normalizedDisplay(ids);
        if(!same(next.get("moldName"),display)) {
            if(seen.contains("moldName")) throw invalid("维护兼容成员时名称由成员自动生成，请不要同时填写不同的名称");
            next.put("moldName",display);derived.add(new CellChange("moldName",json.valueToTree(current.get("moldName")),json.valueToTree(display),"按明确关联的标准手机更新显示名称；原刻码与原始文字不变"));
        }
        return derived;
    }
    private void requireMemberBrand(Object brand,Map<Long,Map<String,Object>> members) {
        if(brand!=null&&members.values().stream().anyMatch(m->!same(brand,m.get("brandId")))) throw invalid("所选品牌与兼容手机成员品牌不一致，请核对后维护");
    }
    private void requireCodeBrand(Long brand,Long code) {
        if(brand==null||code==null)return;
        var brands=jdbc.queryForList("select brand_id from plm_phone_code_brand_effective where code_item_id=?",Long.class,code);
        if(!brands.isEmpty()&&!brands.contains(brand))throw invalid("所选品牌与手机编码的共享品牌不一致，请先核对手机资料");
    }
    @SuppressWarnings("unchecked")
    private Map<Long,Map<String,Object>> relationMap(Object value) {
        var rows=(List<Map<String,Object>>)normalise(field("item","compatiblePhones"),json.valueToTree(value));Map<Long,Map<String,Object>> out=new LinkedHashMap<>();
        rows.forEach(r->out.put(((Number)r.get("phoneModelId")).longValue(),r));return out;
    }
    @SuppressWarnings("unchecked") private void saveMembers(CurrentUser actor,long itemId,Map<String,Object> current,Map<String,Object> next) {
        Map<Long,Map<String,Object>> before=relationMap(current.get("compatiblePhones")),after=relationMap(next.get("compatiblePhones"));
        // Deleting a member cascades only its fit records, never physical sets or costs.
        for(Long removed:before.keySet().stream().filter(id->!after.containsKey(id)).toList()) jdbc.update("delete from plm_collaboration_phone_member where item_id=? and phone_model_id=?",itemId,removed);
        after.forEach((model,relation)->{
            if(same(before.get(model),relation)) return;
            List<Long> priorSets=before.containsKey(model)?(List<Long>)before.get(model).get("setIds"):List.of();
            for(Long setId:(List<Long>)relation.get("setIds"))if(!priorSets.contains(setId))com.yuewei.plm.module.masterdata.ProductMoldLifecycleService.requireActive(jdbc,"mold_set",setId);
            String sqlArray="{"+String.join(",",((List<?>)relation.get("setIds")).stream().map(Object::toString).toList())+"}";
            jdbc.update("insert into plm_collaboration_phone_member(item_id,phone_model_id,mold_set_ids,pc_confirmed,tpu_confirmed,evidence,created_by,updated_by) values(?,?,?::bigint[],?,?,?,?,?) on conflict(item_id,phone_model_id) do update set mold_set_ids=excluded.mold_set_ids,pc_confirmed=excluded.pc_confirmed,tpu_confirmed=excluded.tpu_confirmed,evidence=excluded.evidence,updated_by=excluded.updated_by",itemId,model,sqlArray,relation.get("pcConfirmed"),relation.get("tpuConfirmed"),relation.get("evidence"),actor.username(),actor.username());
            var sets=(List<Map<String,Object>>)relation.get("setCompatibilities");
            jdbc.update("delete from plm_collaboration_phone_set_compatibility where item_id=? and phone_model_id=? and not (mold_set_id=any(?::bigint[]))",itemId,model,sqlArray);
            for(var set:sets) jdbc.update("insert into plm_collaboration_phone_set_compatibility(item_id,phone_model_id,mold_set_id,pc_confirmed,tpu_confirmed,pc_evidence,tpu_evidence,created_by,updated_by) values(?,?,?,?,?,?,?,?,?) on conflict(item_id,phone_model_id,mold_set_id) do update set pc_confirmed=excluded.pc_confirmed,tpu_confirmed=excluded.tpu_confirmed,pc_evidence=excluded.pc_evidence,tpu_evidence=excluded.tpu_evidence,updated_by=excluded.updated_by,updated_at=now()",itemId,model,set.get("moldSetId"),set.get("pcConfirmed"),set.get("tpuConfirmed"),set.get("pcEvidence"),set.get("tpuEvidence"),actor.username(),actor.username());
        });
    }
    private List<CellChange> validateQuote(Map<String,Object> current,Map<String,Object> next,Set<String> seen,RowPatch patch) {
        if(seen.stream().noneMatch(k->k.startsWith("quote"))) return List.of();
        if(seen.contains("quoteConfirmed")&&Boolean.TRUE.equals(next.get("quoteConfirmed"))) for(String dependency:List.of("quoteAmount","quoteCurrency","quoteBasis")) {
            if(!seen.contains(dependency)&&!patch.expectedValues().containsKey(dependency)) throw invalid("确认报价须提交金额、币种及报价依据的原值，请重新打开报价核对");
        }
        if(next.get("quoteConfirmed")==null) throw invalid("报价确认状态不能为空");
        if(next.get("quoteAmount")!=null&&(next.get("quoteCurrency")==null||next.get("quoteBasis")==null)) throw invalid("填写制模报价须同时填写币种及报价依据");
        if(Boolean.TRUE.equals(next.get("quoteConfirmed"))&&(next.get("quoteAmount")==null||next.get("quoteCurrency")==null||next.get("quoteCurrency").equals("UNKNOWN")||next.get("quoteBasis")==null)) throw invalid("确认报价须有金额、明确币种及报价依据");
        boolean changed=List.of("quoteAmount","quoteCurrency","quoteBasis").stream().anyMatch(k->!same(current.get(k),next.get(k)));
        if(changed&&Boolean.TRUE.equals(current.get("quoteConfirmed"))&&patch.changes().stream().filter(c->c.field().startsWith("quote")&&!c.field().equals("quoteConfirmed")).anyMatch(c->c.reason()==null||c.reason().isBlank())) throw invalid("更正确认报价须填写原因，并保留原报价历史");
        if(changed&&Boolean.TRUE.equals(current.get("quoteConfirmed"))&&!seen.contains("quoteConfirmed")) {
            next.put("quoteConfirmed",false);return List.of(new CellChange("quoteConfirmed",json.valueToTree(true),json.valueToTree(false),"报价内容改变，须重新确认"));
        }
        return List.of();
    }
    @SuppressWarnings("unchecked")
    private void protectQuote(Object value) {
        protectDevelopmentScope(value);
        if(access.hasPermission("finance:read")||value==null) return;
        redactQuote(value);
    }
    @SuppressWarnings("unchecked")
    private void protectDevelopmentScope(Object value){
        if(value instanceof Map<?,?> raw){var map=(Map<String,Object>)raw;
            java.util.function.LongPredicate canRead=project->{try{access.requireProduct(project);return true;}catch(com.yuewei.plm.common.exception.BusinessException denied){return false;}};
            if("developmentNode".equals(map.get("fieldName")))for(String field:List.of("before","after"))if(map.containsKey(field))map.put(field,com.yuewei.plm.module.project.development.FinancialSnapshotRedaction.scopedMold(map.get(field),canRead));
            if(map.containsKey("development"))map.put("development",com.yuewei.plm.module.project.development.FinancialSnapshotRedaction.scopedMold(map.get("development"),canRead));
            map.values().forEach(this::protectDevelopmentScope);
        }else if(value instanceof Collection<?> rows)rows.forEach(this::protectDevelopmentScope);
    }
    void protectQuoteResponse(Object value) {protectQuote(value);}
    PageVO<Map<String,Object>> protectHistoryResponse(PageVO<Map<String,Object>> page) {return protectHistory(page);}
    @SuppressWarnings("unchecked")
    private void redactQuote(Object value) {
        if(value instanceof Map<?,?> raw) {
            Map<String,Object> map=(Map<String,Object>)raw;
            if("developmentNode".equals(map.get("fieldName")))for(String field:List.of("before","after"))if(map.containsKey(field))map.put(field,com.yuewei.plm.module.project.development.FinancialSnapshotRedaction.withoutMoney(map.get(field)));
            if(map.containsKey("development"))map.put("development",com.yuewei.plm.module.project.development.FinancialSnapshotRedaction.withoutMoney(map.get("development")));
            map.keySet().removeIf(key->key.startsWith("quote"));
            if(Objects.toString(map.get("fieldName"),"").startsWith("quote")) {map.remove("before");map.remove("after");map.remove("reason");map.put("redacted",true);}
            if(Objects.toString(map.get("field"),"").startsWith("quote")) {map.remove("before");map.remove("current");map.remove("attempted");map.put("redacted",true);}
            map.values().forEach(this::redactQuote);
        } else if(value instanceof Collection<?> rows) rows.forEach(this::redactQuote);
    }
    private PageVO<Map<String,Object>> protectHistory(PageVO<Map<String,Object>> page) {protectQuote(page.getContent());return page;}
    private void validateParts(CurrentUser actor,Map<String,Object> current,Map<String,Object> next,List<CellChange> changes) {
        for(String prefix:List.of("pc","tpu")) {
            String status=Objects.toString(next.get(prefix+"AcceptanceStatus"),"pending");
            Object arrived=next.get(prefix+"FactoryArrivalDate"),accepted=next.get(prefix+"AcceptanceDate");
            String part=prefix.equals("pc")?"PC":"TPU";
            if(status.equals("passed")&&(arrived==null||accepted==null)) throw invalid(part+" 验收通过须填写实际到厂日期和验收日期");
            if(arrived!=null&&accepted!=null&&parseDate(accepted.toString()).isBefore(parseDate(arrived.toString()))) throw invalid(part+" 验收日期不能早于实际到厂日期");
            if(status.equals("failed")&&(next.get(prefix+"AcceptanceIssues")==null||next.get(prefix+"AcceptanceIssues").toString().isBlank())) throw invalid(part+" 验收不通过须填写问题说明");
            Object masterId=next.get(prefix+"MoldCodeId"),raw=next.get(prefix+"OriginalMoldCode"),inventoryId=next.get(prefix+"InventoryId");
            if(Boolean.TRUE.equals(current.get("engravedCodeConfirmed"))) for(String key:List.of(prefix+"MoldCodeId",prefix+"OriginalMoldCode")) {
                if(!same(current.get(key),next.get(key))) {
                    if(!actor.allPermissions()) throw new BusinessException(40103,"已确认实物刻码固定保留，只有管理员可更正录入错误");
                    if(changes.stream().filter(c->c.field().equals(key)).noneMatch(c->c.reason()!=null&&!c.reason().isBlank())) throw invalid("更正已确认原刻码须说明录入错误原因；不会改变实物刻码");
                }
            }
            String masterCode=null;
            if(masterId!=null) masterCode=jdbc.queryForObject("select mold_code from plm_product_mold_code where product_mold_code_id=?",String.class,masterId);
            if(masterCode!=null&&raw!=null&&!masterCode.equals(raw.toString())) throw invalid(part+" 原模具编码与所选编码资料不一致，请保留来源原值");
            if(inventoryId!=null) {
                List<String> codes=jdbc.queryForList("select inventory_code from plm_inventory where inventory_id=? and deleted_flag=0",String.class,inventoryId);
                if(codes.isEmpty()) throw invalid(part+" 历史库存资料不存在");
                String effective=masterCode!=null?masterCode:Objects.toString(raw,null);
                if(!codes.get(0).equals(effective)) throw invalid(part+" 原模具编码须与人工选择的库存资料原码一致");
            }
            if(!same(current.get(prefix+"InventoryId"),inventoryId)&&changes.stream().filter(c->c.field().equals(prefix+"InventoryId")).noneMatch(c->c.reason()!=null&&!c.reason().isBlank()))
                throw invalid(part+" 库存资料关联须填写人工核对说明；不能按库存分类或数量自动建模具");
        }
    }
    public Map<String,Object> linkProjects(long itemId,LinkProjects dto,HttpServletRequest request) {
        read();CurrentUser actor=actor();requireGroup(actor,"market");access.requireItem(itemId);
        return new TransactionTemplate(transactionManager).execute(tx-> {
            lock("item",itemId);Map<String,Object> item=repository.row("item",itemId);
            List<Long> currentIds=((List<Map<String,Object>>)item.get("projects")).stream().map(p->((Number)p.get("projectId")).longValue()).sorted().toList();
            if(!dto.append() && (dto.beforeProjectIds()==null || !currentIds.equals(dto.beforeProjectIds().stream().distinct().sorted().toList())))
                throw invalid("项目关联已被其他人修改，请重新打开并核对");
            Set<Long> selected=new LinkedHashSet<>();if(dto.append()) selected.addAll(currentIds);selected.addAll(dto.projectIds());
            List<Long> ids=List.copyOf(selected);
            List<Long> memberIds=((List<Number>)item.get("compatiblePhoneModelIds")).stream().map(Number::longValue).toList();
            Map<Long,Map<String,Object>> members=memberIds.isEmpty()?Map.of():phones.members(memberIds,true);
            Map<Long,Map<String,Object>> memberBrands=new HashMap<>();
            members.values().forEach(m->memberBrands.computeIfAbsent(((Number)m.get("brandId")).longValue(),phones::brand));
            for(Long id:ids) {
                access.requireProduct(id);
                List<Map<String,Object>> rows=jdbc.queryForList("select parent_product_id,model,phone_model_code,phone_model_member_id from plm_product where product_id=? and product_type='model_variant' and deleted_flag=0",id);
                if(rows.isEmpty()) throw invalid("只能关联已有型号项目");Map<String,Object> project=rows.get(0);
                if(!same(item.get("productLineId"),project.get("parent_product_id"))) throw invalid("项目所属产品线与协作档案不一致");
                // Already-linked historical projects retain their original snapshots, even after master retirement.
                if(!currentIds.contains(id)) {
                    com.yuewei.plm.module.masterdata.ProductMoldLifecycleService.requireActive(jdbc,"item",itemId);
                    com.yuewei.plm.module.masterdata.ProductMoldLifecycleService.requireActive(jdbc,"product",id);
                    if(!members.isEmpty()) {
                        String projectModel=Objects.toString(project.get("model"),"").strip();
                        var matching=members.values().stream().filter(m->same(project.get("phone_model_code"),m.get("phoneModelCode")))
                            .filter(m->project.get("phone_model_member_id")==null||same(project.get("phone_model_member_id"),m.get("phoneModelId")))
                            .filter(m->{List<String> names=new ArrayList<>(List.of(m.get("modelName").toString(),m.get("displayName").toString()));
                                names.addAll((List<String>)m.get("aliases"));var brand=memberBrands.get(((Number)m.get("brandId")).longValue());String key=PhoneIdentityMatcher.modelKey(projectModel,brand);return names.stream().anyMatch(name->PhoneIdentityMatcher.modelKey(name,brand).equals(key));}).toList();
                        if(matching.size()!=1) throw invalid("新增项目须明确匹配唯一兼容手机成员及其原编码，请核对标准名称或别名；不能按相同字符串猜配");
                        var member=matching.get(0);
                        if(!"enabled".equals(member.get("status"))||!"enabled".equals(member.get("brandStatus"))||member.get("codeItemId")!=null&&(!"enabled".equals(member.get("codeStatus"))||!same(member.get("codeDeletedFlag"),0)))
                            throw invalid("新增项目关联须使用启用的手机、品牌及原编码；停用或软删除记录只保留历史关联");
                    } else if(project.get("phone_model_code")!=null&&item.get("phoneModelCodeItemId")!=null) {
                        String code=validatePhoneLink(((Number)item.get("phoneModelCodeItemId")).longValue(),true);
                        if(!same(code,project.get("phone_model_code"))) throw invalid("项目手机型号编码与原基础资料不一致，请先人工核对共模关系");
                    }
                    boolean exact=Objects.toString(item.get("compatibleModels"),"").trim().equalsIgnoreCase(Objects.toString(project.get("model"),"").trim());
                    if(!exact&&!dto.confirmCompatibility()) throw invalid("型号名称不一致，请人工确认兼容关系");
                }
                List<Long> owners=jdbc.queryForList("select item_id from plm_collaboration_item_project where project_id=?",Long.class,id);
                if(!owners.isEmpty()&&!owners.get(0).equals(itemId)) throw invalid("该项目已关联其他协作档案，不能自动转移");
            }
            if(new TreeSet<>(ids).equals(new TreeSet<>(currentIds))) return repository.row("item",itemId);
            Object before=item.get("projects");
            for(Long removed:currentIds.stream().filter(id->!selected.contains(id)).toList()) jdbc.update("delete from plm_collaboration_item_project where item_id=? and project_id=?",itemId,removed);
            for(Long id:ids.stream().filter(project->!currentIds.contains(project)).toList()) jdbc.update("insert into plm_collaboration_item_project(item_id,project_id,linked_by) values(?,?,?)",itemId,id,actor.username());
            jdbc.update("update plm_collaboration_item set master_data_version=master_data_version+1,updated_at=now(),updated_by=? where item_id=?",actor.username(),itemId);
            history(actor,itemId,"item",itemId,"projects",before,ids,"人工确认项目关联");
            log(actor,itemId,"COLLABORATION_LINK",ids,request);return repository.row("item",itemId);
        });
    }
    public Map<String,Object> assignments() {
        CurrentUser actor=actor();admin(actor);
        return Map.of("groups",assignmentMap(),"users",repository.users());
    }
    public List<Map<String,Object>> accounts() {admin(actor());return repository.users();}
    public Map<String,Object> initialiseExplicitBrands(HttpServletRequest request) {
        admin(actor());List<RowPatch> changes=new ArrayList<>();
        for(Map<String,Object> row:repository.previewRows("item")) {
            if(row.get("brand")!=null) continue;String brand=brandFromModel(Objects.toString(row.get("compatibleModels"),null));
            if(brand!=null) changes.add(new RowPatch("item",((Number)row.get("itemId")).longValue(),List.of(new CellChange("brand",json.nullNode(),json.valueToTree(brand),"根据型号名称中的明确品牌初始化；不改动已有品牌或历史项目"))));
        }
        List<RowResult> results=changes.isEmpty()?List.of():save(new BatchSave(changes),request);
        return Map.of("updated",results.stream().filter(r->r.status().equals("saved")).count(),"notSaved",results.stream().filter(r->!r.status().equals("saved")).count());
    }
    private Map<String,List<Long>> assignmentMap() {
        Map<String,List<Long>> assignments=new LinkedHashMap<>();GROUPS.forEach(g->assignments.put(g,new ArrayList<>()));
        jdbc.queryForList("select field_group,user_id from plm_collaboration_assignment order by field_group,user_id")
            .forEach(row->assignments.get(row.get("field_group").toString()).add(((Number)row.get("user_id")).longValue()));
        return assignments;
    }
    public Map<String,Object> saveAssignments(Assignments dto,HttpServletRequest request) {
        CurrentUser actor=actor();admin(actor);
        return new TransactionTemplate(transactionManager).execute(tx-> {
            jdbc.queryForObject("select pg_advisory_xact_lock(610091001)",(rs,n)->true);
            Map<String,List<Long>> before=assignmentMap();
            if(!normalAssignment(before).equals(normalAssignment(dto.before()))) throw invalid("职责配置已被其他管理员修改，请刷新后重新配置");
            Map<String,List<Long>> next=normalAssignment(dto.groups());
            Set<Long> active=new HashSet<>(repository.users().stream().map(u->((Number)u.get("userId")).longValue()).toList());
            if(next.values().stream().flatMap(List::stream).anyMatch(id->!active.contains(id))) throw invalid("只能分配给有效的真实账号");
            Set<Long> changedUsers=new TreeSet<>();
            for(String group:GROUPS) {
                Set<Long> previous=new HashSet<>(before.getOrDefault(group,List.of())),current=new HashSet<>(next.get(group));
                previous.stream().filter(id->!current.contains(id)).forEach(changedUsers::add);
                current.stream().filter(id->!previous.contains(id)).forEach(changedUsers::add);
            }
            jdbc.update("delete from plm_collaboration_assignment");
            next.forEach((g,ids)->ids.forEach(id->jdbc.update("insert into plm_collaboration_assignment(field_group,user_id) values(?,?)",g,id)));
            if(!changedUsers.isEmpty()) {
                List<Object> arguments=new ArrayList<>();arguments.add(actor.username());arguments.addAll(changedUsers);
                jdbc.update("update plm_pilot_account set version_no=version_no+1,updated_at=now(),updated_by=? where user_id in ("+
                    String.join(",",Collections.nCopies(changedUsers.size(),"?"))+")",arguments.toArray());
            }
            log(actor,0,"COLLABORATION_ASSIGNMENTS",Map.of("before",before,"after",next),request);
            return Map.of("groups",next,"users",repository.users());
        });
    }
    private Map<String,List<Long>> normalAssignment(Map<String,List<Long>> input) {
        if(input.keySet().stream().anyMatch(g->!GROUPS.contains(g))) throw invalid("未知字段组");
        Map<String,List<Long>> out=new LinkedHashMap<>();
        for(String group:GROUPS) {List<Long> ids=input.getOrDefault(group,List.of());if(ids==null||ids.size()>100) throw invalid("账号列表无效");out.put(group,ids.stream().filter(Objects::nonNull).distinct().sorted().toList());}
        return out;
    }
    private void validateLine(Long line) {
        if(line!=null&&jdbc.queryForObject("select count(*) from plm_product where product_id=? and deleted_flag=0 and product_type='product_line'",Long.class,line)==0) throw invalid("产品线不存在");
    }
    private void lockIdentity(String style,String models) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(lower(btrim(?)) || chr(31) || lower(btrim(?)),610091002))",(rs,n)->true,style,models);
    }
    private void requireNewLine(Long line) {
        if(line!=null)com.yuewei.plm.module.masterdata.ProductMoldLifecycleService.requireActive(jdbc,"product",line);
        if(access.isRestricted()&&!access.access().allObjects()&&line==null) throw new BusinessException(40103,"限定对象账号的新档案须选择已授权产品平台");
        if(line!=null) access.requireProduct(line);
    }
    private Long resolveLegacyPhoneCode(Object code,String model) {
        if(code==null) return null;
        List<Long> ids=jdbc.queryForList("select code_item_id from plm_code_item where code_type='phone_model' and code_value=? and lower(btrim(code_name))=lower(btrim(?)) and deleted_flag=0 and status='enabled'",Long.class,code.toString(),model);
        if(ids.size()!=1) throw invalid("手机型号编码与名称尚未确认一一对应，请从基础资料明确选择型号 / 共模组编码");
        return ids.get(0);
    }
    private String validatePhoneLink(Long id,boolean newLink) {
        if(id==null) return null;
        var rows=jdbc.queryForList("select code_value,status from plm_code_item where code_item_id=? and code_type='phone_model' and deleted_flag=0 for share",id);
        if(rows.isEmpty()||(newLink&&!rows.get(0).get("status").equals("enabled"))) throw invalid("请选择有效且已启用的手机型号 / 共模组编码；停用记录仅保留原有关联");
        return rows.get(0).get("code_value").toString();
    }
    private void requireCodeSnapshot(Object raw,String linked) {
        if(raw!=null&&!raw.toString().equals(linked)) throw invalid("原手机型号编码与所选基础资料不一致，请人工核对；不会自动覆盖历史编码");
    }
    private List<CellChange> alignPhoneLink(Map<String,Object> current,Map<String,Object> next,Set<String> seen) {
        requirePhoneAnchor(current,next);
        List<CellChange> changes=new ArrayList<>();
        boolean fillingKnownSnapshot=current.get("phoneModelCode")==null&&current.get("phoneModelCodeItemId")!=null&&same(next.get("phoneModelCode"),current.get("linkedPhoneModelCode"));
        if(seen.contains("phoneModelCode")&&!same(current.get("phoneModelCode"),next.get("phoneModelCode"))&&!seen.contains("phoneModelCodeItemId")&&!fillingKnownSnapshot) {
            Long id=resolveLegacyPhoneCode(next.get("phoneModelCode"),next.get("compatibleModels").toString());
            next.put("phoneModelCodeItemId",id);changes.add(new CellChange("phoneModelCodeItemId",json.valueToTree(current.get("phoneModelCodeItemId")),json.valueToTree(id),"旧编码入口按明确编码及名称关联基础资料"));
        }
        requirePhoneAnchor(current,next);
        Long id=(Long)next.get("phoneModelCodeItemId");
        boolean changed=!same(current.get("phoneModelCodeItemId"),id);
        if(changed||seen.contains("phoneModelCode")) {
            String linked=validatePhoneLink(id,changed);
            if(linked!=null) {
                requireCodeSnapshot(next.get("phoneModelCode"),linked);
                if(next.get("phoneModelCode")==null) {
                    next.put("phoneModelCode",linked);
                    changes.removeIf(c->c.field().equals("phoneModelCode"));
                    if(!seen.contains("phoneModelCode")) changes.add(new CellChange("phoneModelCode",json.valueToTree(current.get("phoneModelCode")),json.valueToTree(linked),"首次关联基础资料，保存编码原值快照"));
                }
            }
        }
        return changes;
    }
    private void requirePhoneAnchor(Map<String,Object> current,Map<String,Object> next) {
        if(current.get("moldName")==null&&!Boolean.TRUE.equals(current.get("engravedCodeConfirmed"))&&((Number)current.get("moldSetCount")).longValue()==0&&((List<?>)current.get("compatiblePhoneModelIds")).isEmpty()) return;
        for(String key:List.of("phoneModelCodeItemId","phoneModelCode")) if(current.get(key)!=null&&!same(current.get(key),next.get(key))) throw invalid("已确认共模档案的原手机编码锚点固定保留；新增兼容手机请维护成员关系，不能换码或清空历史锚点");
    }
    private void requireIdentity(Long exclude,Long line,Long phoneId,String style,String model) {
        List<Object> args=new ArrayList<>();String identity;
        if(line!=null&&phoneId!=null) {
            jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(? || chr(31) || ?,610131001))",(rs,n)->true,line.toString(),phoneId.toString());
            identity="product_line_id=? and phone_model_code_item_id=? and mold_name is null";args.add(line);args.add(phoneId);
        } else {
            lockIdentity(style,model);identity="(product_line_id is null or phone_model_code_item_id is null) and lower(btrim(style_name))=lower(?) and lower(btrim(compatible_models))=lower(?)";args.add(style);args.add(model);
        }
        if(exclude!=null) {identity+=" and item_id<>?";args.add(exclude);}
        Long duplicates=jdbc.queryForObject("select count(*) from plm_collaboration_item where deleted_flag=0 and "+identity,Long.class,args.toArray());
        if(duplicates!=null&&duplicates>0) throw invalid("该产品平台与型号已有档案，请关联原档案；复制模具请新增模具套，不重复累计产量");
    }
    private void validateMoldCode(long itemId,Long codeId) {
        if(codeId==null) return;
        Long count=jdbc.queryForObject("""
            select count(*) from plm_product_mold_code mc where mc.product_mold_code_id=? and mc.deleted_flag=0
             and (exists(select 1 from plm_collaboration_item_project ip where ip.item_id=? and ip.project_id=mc.product_id)
               or mc.product_id=(select product_line_id from plm_collaboration_item where item_id=?))
            """,Long.class,codeId,itemId,itemId);
        if(count==null||count==0) throw invalid("模具编码不属于已关联项目或产品线");
    }
    private void lock(String entity,long id) {
        fields(entity);
        List<Long> ids=jdbc.queryForList("select "+idColumn(entity)+" from "+table(entity)+" where "+idColumn(entity)+"=? and deleted_flag=0 for update",Long.class,id);
        if(ids.isEmpty()) throw new BusinessException(40401,"协作记录不存在");
    }
    private static String table(String entity) {return "item".equals(entity)?"plm_collaboration_item":"plm_collaboration_mold_set";}
    private static String idColumn(String entity) {return "item".equals(entity)?"item_id":"mold_set_id";}
    private static Object sqlValue(Object value) {return value instanceof LocalDate d?java.sql.Date.valueOf(d):value;}
    private void history(CurrentUser actor,long itemId,String entity,long id,String field,Object before,Object after,String reason) {
        jdbc.update("insert into plm_collaboration_history(item_id,entity_type,entity_id,field_name,before_value,after_value,reason,actor_id,actor_name) values(?,?,?,?,?::jsonb,?::jsonb,?,?,?)",itemId,entity,id,field,encode(before),encode(after),reason,actor.userId(),actor.username());
    }
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot encode audit value",e);}}
    private void log(CurrentUser actor,long itemId,String action,Object data,HttpServletRequest request) {
        operationLog.logRequired(OperationLogCreateCommand.builder().action(action).businessType("COLLABORATION")
            .businessId(String.valueOf(itemId)).businessName("协作档案与模具").detailJson(encode(data)).request(request).build(),actor);
    }
}
