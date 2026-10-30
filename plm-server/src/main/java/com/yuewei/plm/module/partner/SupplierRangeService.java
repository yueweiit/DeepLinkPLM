package com.yuewei.plm.module.partner;

import com.yuewei.plm.module.moldreference.PhoneModelReferenceService;
import com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand;
import java.util.*;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Supplier ranges are reusable references; only explicit pairings declare actual supply. */
@Service @RequiredArgsConstructor
public class SupplierRangeService {
    private final PartnerStore store;
    private final SupplierProfileService suppliers;
    private final PartnerOperationsAccess permissions;
    private final PhoneModelReferenceService phones;

    private Map<String,Object> view(String supplier) {
        store.access.requireDomesticView();store.access.requirePermission("supplier:view");
        return suppliers.requireSupplier(required(supplier,"供应商"));
    }
    private void write(String supplier) { permissions.engineer();view(supplier); }

    private static final List<String> COMMON_COLORS=List.of("黑色","白色","透明","磨砂透明","烟灰色","灰色","银色","金色","香槟金","玫瑰金","红色","酒红色","粉色","玫红色","橙色","黄色","绿色","薄荷绿","蓝色","藏蓝色","紫色","浅紫色","棕色","卡其色");
    public Map<String,Object> colors(String supplier,Map<String,String> query) { var result=new LinkedHashMap<>(page(supplier,query,false));result.put("commonColors",COMMON_COLORS);return result; }
    public Map<String,Object> ranges(String supplier,Map<String,String> query) { return page(supplier,query,true); }
    private Map<String,Object> page(String supplier,Map<String,String> query,boolean range) {
        var owner=view(supplier);String table=range?"plm_supplier_range":"plm_supplier_color",id=range?"range_id":"color_id";
        List<String> where=new ArrayList<>(List.of("supplier_code=?"));List<Object> args=new ArrayList<>(List.of(supplier));
        String word=text(query.get("keyword"));if(!word.isEmpty()){where.add("(name ilike ? or code ilike ? or "+(range?"exists(select 1 from jsonb_array_elements_text(coalesce(data->'aliases','[]')) a where a ilike ?)":"supplier_color_code ilike ?")+")");for(int i=0;i<3;i++)args.add("%"+word+"%");}
        if(range&&!text(query.get("type")).isEmpty()){where.add("type=?");args.add(type(query.get("type")));}
        if(!text(query.get("status")).isEmpty()){where.add("status=?");args.add(query.get("status"));}
        if(!range&&"general".equals(query.get("scope")))where.add("style_key='' and config_key=''");
        long page=Math.max(1,number(query.getOrDefault("page","1"))),size=Math.max(1,Math.min(200,number(query.getOrDefault("size","50"))));
        require(page<=100000000,"页码超出范围");String condition=String.join(" and ",where);
        long total=store.jdbc.queryForObject("select count(*) from "+table+" where "+condition,Long.class,args.toArray());
        args.add(size);args.add((page-1)*size);
        var rows=store.jdbc.queryForList("select * from "+table+" where "+condition+" order by "+id+" desc limit ? offset ?",args.toArray());
        return Map.of("content",snapshots(rows,range),"totalElements",total,"page",page,"size",size,"supplier",owner,"canWrite",permissions.engineering());
    }

    public Map<String,Object> previewColors(String supplier,Map<String,Object> input) {
        write(supplier);List<Map<String,Object>> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(var item:items(input)) {
            var row=new LinkedHashMap<String,Object>(item);String name=text(item.get("name"));
            if(name.isEmpty()){row.put("state","missing");row.put("candidates",List.of());}
            else {
                String external=text(item.get("supplierColorCode"));
                var candidates=findColors(supplier,external.isEmpty()?name:external,text(item.get("styleKey")),text(item.get("configKey")),true);
                String identity=List.of(name.toLowerCase(Locale.ROOT),text(item.get("supplierColorCode")),text(item.get("styleKey")),text(item.get("configKey")),signature(store.json,item.getOrDefault("refs",List.of()))).toString();
                row.put("state",!seen.add(identity)?"duplicate":candidates.isEmpty()?"new":candidates.size()==1?"matched":"ambiguous");row.put("candidates",candidates);
            }
            result.add(row);
        }
        return Map.of("rows",result,"supplier",view(supplier));
    }

    /** A paste/list preview is deliberately non-writing, including unknown and ambiguous phone names. */
    public Map<String,Object> preview(String supplier,Map<String,Object> input) {
        write(supplier);String kind=type(input.get("type")),style=text(input.get("styleKey")),config=text(input.get("configKey"));
        List<Object> values=new ArrayList<>();Object raw=input.get("values");
        if(raw instanceof String s)values.addAll(Arrays.asList(s.split("\\R",-1)));else if(raw instanceof List<?> list)values.addAll(list);else require(raw==null,"请粘贴多行名称／编码或传入列表");
        require(input.get("memberIds")==null||input.get("memberIds") instanceof List<?>,"已选成员须为列表");
        if(input.get("memberIds") instanceof List<?> ids)for(Object id:ids)values.add(Map.of("id",id));
        List<Map<String,Object>> rows=new ArrayList<>(),members=new ArrayList<>();Set<Long> seen=new HashSet<>();
        for(Object value:values) {
            String original=value instanceof Map<?,?>?text(map(value).get("id")):text(value);if(original.isEmpty())continue;
            List<Map<String,Object>> candidates=List.of();String issue="";
            try {
                if(value instanceof Map<?,?>) {
                    require(map(value).get("id") instanceof Number,"成员ID须为系统数值；型号编码或名称请粘贴到待匹配列表");long id=number(map(value).get("id"));
                    if(kind.equals("model"))candidates=new ArrayList<>(phoneMembers(List.of(id),true).values());
                    else candidates=List.of(color(supplier,id,true));
                } else candidates=kind.equals("model")?findPhones(original):findColors(supplier,original,style,config,true);
            }catch(com.yuewei.plm.common.exception.BusinessException e){issue=e.getMessage();}
            var row=new LinkedHashMap<String,Object>();row.put("raw",original);row.put("candidates",candidates);
            if(!issue.isEmpty())row.put("message",issue);
            if(candidates.size()!=1)row.put("state",candidates.isEmpty()?"missing":"ambiguous");
            else {var member=candidates.get(0);long id=number(member.get("id"));row.put("member",member);if(seen.add(id)){row.put("state","matched");members.add(member);}else row.put("state","duplicate");}
            rows.add(row);
        }
        long unresolved=rows.stream().filter(r->Set.of("missing","ambiguous").contains(text(r.get("state")))).count();
        return Map.of("rows",rows,"members",members,"unresolvedCount",unresolved,"supplier",view(supplier));
    }

    @Transactional public Map<String,Object> saveColors(String supplier,Map<String,Object> input) {
        return change(supplier,"colors",input,()-> {
            List<Map<String,Object>> result=new ArrayList<>();
            for(var item:items(input)) {
                long id=number(item.get("id"));var old=id>0?color(supplier,id,true):Map.<String,Object>of();checkVersion(old,item);
                String name=bounded(required(item.get("name"),"供方原色名称"),255),external=bounded(text(item.get("supplierColorCode")),128);
                String style=bounded(text(item.get("styleKey")),255),config=bounded(text(item.get("configKey")),255),status=text(item.getOrDefault("status","active"));
                require(Set.of("active","inactive").contains(status),"原色状态无效");
                long standard=number(item.get("standardColorId"));if(standard>0)require(Boolean.TRUE.equals(store.jdbc.queryForObject("select exists(select 1 from plm_code_item where code_item_id=? and code_type='color' and deleted_flag=0)",Boolean.class,standard)),"标准颜色引用不存在；不自动创建颜色码");
                var data=new LinkedHashMap<String,Object>();data.put("standardColorId",standard);data.put("notes",text(item.get("notes")));data.put("refs",fileRefs(item.get("refs")));
                var identical=store.jdbc.queryForList("select color_id from plm_supplier_color where supplier_code=? and color_id<>? and lower(name)=lower(?) and supplier_color_code=? and style_key=? and config_key=? and data->'refs'=?::jsonb",Long.class,supplier,id,name,external,style,config,encode(store.json,data.get("refs")));
                if(id==0&&Boolean.TRUE.equals(input.get("reuseExisting"))&&!identical.isEmpty()){
                    require(identical.size()==1,"这个颜色有多条记录，请选择对应色号或色卡");result.add(color(supplier,identical.get(0),true));continue;
                }
                require(identical.isEmpty(),"该供方原色已存在，请明确选中已有颜色，不重复创建");
                if(id==0)id=store.jdbc.queryForObject("insert into plm_supplier_color(supplier_code,code,name,supplier_color_code,style_key,config_key,status,data,created_by,updated_by) values(?,?,?,?,?,?,?,?::jsonb,?,?) returning color_id",Long.class,supplier,UUID.randomUUID().toString(),name,external,style,config,status,encode(store.json,data),actor(),actor());
                else store.jdbc.update("update plm_supplier_color set name=?,supplier_color_code=?,style_key=?,config_key=?,status=?,data=?::jsonb,version=version+1,updated_at=now(),updated_by=? where color_id=?",name,external,style,config,status,encode(store.json,data),actor(),id);
                if(old.isEmpty())store.jdbc.update("update plm_supplier_color set code=? where color_id=?","SC-"+String.format("%06d",id),id);
                var saved=color(supplier,id,true);store.jdbc.update("insert into plm_supplier_color_version(color_id,version,snapshot,created_by) values(?,?,?::jsonb,?)",id,saved.get("version"),encode(store.json,saved),actor());result.add(saved);
            }
            return Map.of("content",result,"supplier",view(supplier));
        });
    }
    public List<Map<String,Object>> catalogColors(String supplier,Object rawIds,Object rawNames,List<Long> previousIds,String style,String config){
        require(rawIds==null||rawIds instanceof List<?>,"供应商颜色须为列表");require(rawNames==null||rawNames instanceof List<?>,"新增颜色须为名称列表");
        var ids=new LinkedHashSet<>(PartnerSelectionService.scopeIds(rawIds));List<?> names=rawNames==null?List.of():(List<?>)rawNames;require(ids.size()+names.size()<=100,"一次最多选择100个颜色");
        if(!names.isEmpty()){
            List<Map<String,Object>> incoming=new ArrayList<>();for(Object name:names){require(name instanceof String,"颜色名称须为文字");incoming.add(Map.of("name",name));}
            var saved=saveColors(supplier,Map.of("requestId",UUID.randomUUID().toString(),"reason","保存产品时新增供应商颜色；不创建标准色码或供货配对","reuseExisting",true,"items",incoming));
            for(var color:maps(saved.get("content")))ids.add(number(color.get("id")));
        }
        var values=colorMembers(supplier,new ArrayList<>(ids),true);List<Map<String,Object>> result=new ArrayList<>();
        for(long id:ids){var color=values.get(id);require(previousIds.contains(id)||"active".equals(color.get("status")),"供应商颜色已停用，不能新增选用");require(applies(color,style,config),"颜色不适用于本款产品，请选择通用颜色或对应色卡");result.add(color);}return result;
    }

    @Transactional public Map<String,Object> saveRanges(String supplier,Map<String,Object> input) {
        return change(supplier,"ranges",input,()-> {
            List<Map<String,Object>> result=new ArrayList<>();
            for(var item:items(input)) {
                long id=number(item.get("id"));var old=id>0?range(supplier,id,true):Map.<String,Object>of();checkVersion(old,item);
                String kind=type(item.get("type"));require(old.isEmpty()||kind.equals(old.get("type")),"已建立的范围类型不能改绑");
                String name=bounded(required(item.get("name"),"范围名称"),255),style=bounded(text(item.get("styleKey")),255),config=bounded(text(item.get("configKey")),255);
                List<String> aliases=stringList(item.get("aliases"));var tokens=new HashSet<>(aliases.stream().map(v->v.toLowerCase(Locale.ROOT)).toList());require(tokens.add(name.toLowerCase(Locale.ROOT)),"范围别名不能与名称重复");
                var neighbors=store.jdbc.queryForList("select * from plm_supplier_range where supplier_code=? and type=? and range_id<>? and (style_key='' or ?='' or style_key=?) and (config_key='' or ?='' or config_key=?)",supplier,kind,id,style,style,config,config);
                for(var neighbor:neighbors){var candidate=snapshot(neighbor,true);Set<String> other=new HashSet<>(stringList(candidate.get("aliases")).stream().map(v->v.toLowerCase(Locale.ROOT)).toList());other.add(text(candidate.get("name")).toLowerCase(Locale.ROOT));require(Collections.disjoint(tokens,other),"适用范围内存在同名／同别名，请区分名称或限定款式配置");}
                String status=text(item.getOrDefault("status","draft"));require(Set.of("draft","active","inactive").contains(status),"范围状态无效");
                List<Long> ids=ids(item.get("members"));if(!old.isEmpty()&&!new HashSet<>(ids(old.get("members"))).containsAll(ids))suppliers.requireSupplierForNewUse(supplier);List<Map<String,Object>> members;
                if(kind.equals("model"))members=new ArrayList<>(phoneMembers(ids,!status.equals("active")).values());
                else {members=new ArrayList<>();for(long member:ids){var value=color(supplier,member,!status.equals("active"));require(applies(value,style,config),"颜色不适用于当前款式／配置");members.add(value);}}
                Object pending=item.getOrDefault("pendingValues",item.get("unresolved"));
                require(pending==null||pending instanceof List<?>,"待核实原文须为列表，不能静默丢弃");
                List<Object> unresolved=pending instanceof List<?> list?new ArrayList<>(list):List.of();
                require(!status.equals("active")||(!members.isEmpty()&&unresolved.isEmpty()),"启用范围须有明确成员，且不能包含未核实项");
                var data=new LinkedHashMap<String,Object>();data.put("aliases",aliases);data.put("members",members);data.put("pendingValues",unresolved);data.put("notes",text(item.get("notes")));
                if(id==0)id=store.jdbc.queryForObject("insert into plm_supplier_range(supplier_code,code,type,name,style_key,config_key,status,data,created_by,updated_by) values(?,?,?,?,?,?,?,?::jsonb,?,?) returning range_id",Long.class,supplier,UUID.randomUUID().toString(),kind,name,style,config,status,encode(store.json,data),actor(),actor());
                else store.jdbc.update("update plm_supplier_range set name=?,style_key=?,config_key=?,status=?,data=?::jsonb,version=version+1,updated_at=now(),updated_by=? where range_id=?",name,style,config,status,encode(store.json,data),actor(),id);
                if(old.isEmpty())store.jdbc.update("update plm_supplier_range set code=? where range_id=?","SR-"+String.format("%06d",id),id);
                var saved=range(supplier,id,true);store.jdbc.update("insert into plm_supplier_range_version(range_id,version,snapshot,created_by) values(?,?,?::jsonb,?)",id,saved.get("version"),encode(store.json,saved),actor());result.add(saved);
            }
            return Map.of("content",result,"supplier",view(supplier));
        });
    }

    public Map<String,Object> metadata(String supplier) {
        var result=view(supplier);result.put("shortCodeHistory",suppliers.shortCodeHistory(supplier));return result;
    }

    @Transactional public Map<String,Object> metadata(String supplier,Map<String,Object> input) {
        return change(supplier,"metadata",input,()-> {
            var old=view(supplier);require(number(old.get("version"))==number(input.get("version")),"供应商已变化，请刷新核对");
            require(input.get("shortCode") instanceof String,"请明确填写供应商简码；暂未确认时可填写空字符串");
            String previous=store.jdbc.queryForObject("select short_code from plm_supplier_profile where supplier_code=?",String.class,supplier);
            String shortCode=SupplierShortCodes.change(input.get("shortCode"),previous);
            try {store.jdbc.update("update plm_supplier_profile set short_code=?,version=version+1,updated_at=now(),updated_by=? where supplier_code=?",shortCode,actor(),supplier);}
            catch(DuplicateKeyException e){throw invalid("供应商简码已被使用或作为历史简码保留，请另选简码");}
            return metadata(supplier);
        });
    }

    public Map<String,Object> resolve(String supplier,Map<String,Object> input) {
        view(supplier);List<Map<String,Object>> snapshots=new ArrayList<>(),errors=new ArrayList<>();
        for(var ref:maps(input.get("references")))try {snapshots.addAll(resolveReferences(supplier,List.of(ref),text(input.get("styleKey")),text(input.get("configKey")),false));}
        catch(com.yuewei.plm.common.exception.BusinessException e){errors.add(Map.of("reference",ref,"message",e.getMessage()));}
        return Map.of("snapshots",snapshots,"errors",errors);
    }

    public List<Map<String,Object>> resolveReferences(String supplier,List<Map<String,Object>> references,String style,String config,boolean allowInactive) {
        view(supplier);lockReadScope(supplier);List<Map<String,Object>> result=new ArrayList<>();Set<Long> seen=new HashSet<>();
        for(var ref:references) {
            String kind=type(ref.get("type")),value=required(ref.get("reference"),"范围名称或编码");
            var rows=store.jdbc.queryForList("select * from plm_supplier_range where supplier_code=? and type=? and (lower(code)=lower(?) or lower(name)=lower(?) or exists(select 1 from jsonb_array_elements_text(coalesce(data->'aliases','[]')) a where lower(a)=lower(?))) and (style_key='' or style_key=?) and (config_key='' or config_key=?)",supplier,kind,value,value,value,text(style),text(config));
            var exactCodes=rows.stream().filter(r->value.equalsIgnoreCase(text(r.get("code")))).toList();if(!exactCodes.isEmpty())rows=exactCodes;
            require(rows.size()==1,rows.isEmpty()?"范围不存在或不适用于当前供应商／款式／配置："+value:"范围名称存在歧义，请选择范围编码："+value);
            var current=snapshot(rows.get(0),true);long id=number(current.get("id")),version=number(ref.getOrDefault("version",current.get("version")));
            var historic=store.jdbc.queryForList("select snapshot::text from plm_supplier_range_version where range_id=? and version=?",id,version);require(historic.size()==1,"范围版本不存在，请重新核对");
            var frozen=decode(store.json,historic.get(0).get("snapshot"));decorateImages(frozen);require(applies(frozen,style,config),"历史范围版本不适用于当前款式／配置");
            require(allowInactive||("active".equals(current.get("status"))&&"active".equals(frozen.get("status"))),"范围未启用或已停用，请核对");
            if(kind.equals("color")&&!allowInactive){var currentColors=colorMembers(supplier,ids(frozen.get("members")),false);for(var member:maps(frozen.get("members")))require(number(member.get("version"))==number(currentColors.get(number(member.get("id"))).get("version")),"范围内供方原色已更新，请核对并保存范围新版本，不能静默换色");}
            frozen.put("currentVersion",current.get("version"));frozen.put("versionChanged",number(current.get("version"))!=version);
            Set<Long> was=new HashSet<>(ids(frozen.get("members"))),now=new HashSet<>(ids(current.get("members")));
            frozen.put("memberChanges",Map.of("added",maps(current.get("members")).stream().filter(m->!was.contains(number(m.get("id")))).toList(),"removed",maps(frozen.get("members")).stream().filter(m->!now.contains(number(m.get("id")))).toList()));
            if(seen.add(id))result.add(frozen);
        }
        return result;
    }

    public List<Map<String,Object>> normalizeSelectedPairs(String supplier,List<Map<String,Object>> pairs,boolean allowInactive) {
        return normalizePairs(supplier,pairs,allowInactive,true);
    }
    private List<Map<String,Object>> normalizePairs(String supplier,List<Map<String,Object>> pairs,boolean allowInactive,boolean unique) {
        view(supplier);lockReadScope(supplier);List<Map<String,Object>> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        var phoneIds=new LinkedHashSet<Long>();var colorIds=new LinkedHashSet<Long>();
        for(var input:pairs) {
            long phone=number(input.get("phoneModelId")),color=number(input.get("colorId"));require(phone>0&&color>0,"请选择实际手机成员和供方颜色");
            require(seen.add(phone+":"+color)||!unique,"型号／颜色组合重复");phoneIds.add(phone);colorIds.add(color);
        }
        var phoneValues=phoneMembers(new ArrayList<>(phoneIds),allowInactive);var colorValues=colorMembers(supplier,new ArrayList<>(colorIds),allowInactive);
        for(var input:pairs) {
            long phone=number(input.get("phoneModelId")),color=number(input.get("colorId"));
            var value=new LinkedHashMap<String,Object>();value.put("phoneModelId",phone);value.put("colorId",color);
            value.put("phoneSnapshot",phoneValues.get(phone));value.put("colorSnapshot",colorValues.get(color));result.add(value);
        }
        return result;
    }
    public List<Map<String,Object>> normalizePairings(String supplier,List<Map<String,Object>> pairings,String style,String config,String quoteVersion,boolean allowInactive) {
        return normalizePairings(supplier,pairings,style,config,quoteVersion,allowInactive,"");
    }
    public List<Map<String,Object>> normalizePairings(String supplier,List<Map<String,Object>> pairings,String style,String config,String quoteVersion,boolean allowInactive,String sourceContext) {
        List<Map<String,Object>> result=new ArrayList<>();var normalized=normalizePairs(supplier,pairings,allowInactive,false);
        for(int i=0;i<pairings.size();i++) {
            var input=pairings.get(i);var pair=normalized.get(i);require(applies(map(pair.get("colorSnapshot")),style,config),"供方颜色不适用于当前款式／配置");
            for(String key:List.of("supplierCode","styleKey","configKey","quoteVersion","state","evidence","unitPrice","currency","packaging","terms","moq","unit","priceNote","sourcePosition"))pair.put(key,text(input.get(key)));
            String supplied=text(input.get("sourceContext"));
            if(!sourceContext.isEmpty()){
                require(supplied.isEmpty()||sourceContext.equals(supplied),"供货关系的已冻结来源版本不符");
                pair.put("sourceContext",sourceContext);
            }else require(supplied.isEmpty(),"供货关系没有对应的已冻结来源");
            result.add(pair);
        }
        // Validate scope and conflicting declarations even when this draft has not selected anything yet.
        SupplierSupplyRules.coverage(supplier,style,config,quoteVersion,result,List.of(),sourceContext);return result;
    }

    private Map<String,Object> change(String supplier,String action,Map<String,Object> input,Supplier<Map<String,Object>> work) {
        permissions.engineer();store.access.requireDomesticView();store.access.requirePermission("supplier:view");
        require(!store.jdbc.queryForList("select supplier_id from plm_supplier_profile where supplier_code=? and deleted_flag=0 for update",supplier).isEmpty(),"供应商不存在或已删除");view(supplier);String request=bounded(required(input.get("requestId"),"提交标识"),80),reason=required(input.get("reason"),"本次变更原因");
        // Serialise metadata/range changes per visible supplier and keep request replay in the same transaction.
        if(!action.equals("metadata")&&items(input).stream().anyMatch(item->number(item.get("id"))==0))suppliers.requireSupplierForNewUse(supplier);
        String payload=signature(store.json,input);var previous=store.jdbc.queryForList("select action,payload,result::text from plm_supplier_range_request where supplier_code=? and actor_id=? and request_id=?",supplier,store.access.actorId(),request);
        if(!previous.isEmpty()){var old=previous.get(0);require(action.equals(old.get("action"))&&payload.equals(old.get("payload")),"提交标识已用于不同内容，请重新核对");var replay=decode(store.json,old.get("result"));decorateImages(replay);return replay;}
        List<Map<String,Object>> before=new ArrayList<>();
        if(action.equals("metadata"))before.add(view(supplier));
        else for(var item:items(input))if(number(item.get("id"))>0)before.add(action.equals("colors")?color(supplier,number(item.get("id")),true):range(supplier,number(item.get("id")),true));
        var result=work.get();var details=Map.of("supplierCode",supplier,"action",action,"reason",reason,"before",before,"after",result,"changes",input);
        store.jdbc.update("insert into plm_partner_event(supplier_code,action,data,request_id,created_by,actor_id) values(?,?,?::jsonb,?,?,?)",supplier,"supplier_"+action,encode(store.json,details),request,actor(),store.access.actorId());
        store.audit.logRequired(OperationLogCreateCommand.builder().action("SUPPLIER_"+action.toUpperCase(Locale.ROOT)).businessType("SUPPLIER").businessId(supplier).detailJson(encode(store.json,details)).build(),store.access.actor());
        store.jdbc.update("insert into plm_supplier_range_request(supplier_code,actor_id,request_id,action,payload,result) values(?,?,?,?,?,?::jsonb)",supplier,store.access.actorId(),request,action,payload,encode(store.json,result));return copy(store.json,result);
    }
    private String actor(){return store.access.actor().username();}
    private void lockReadScope(String supplier){if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()&&!org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())store.jdbc.queryForList("select supplier_id from plm_supplier_profile where supplier_code=? for share",supplier);}
    private Map<Long,Map<String,Object>> colorMembers(String supplier,List<Long> ids,boolean allowInactive){
        Map<Long,Map<String,Object>> result=new HashMap<>();
        for(int start=0;start<ids.size();start+=200){var chunk=ids.subList(start,Math.min(start+200,ids.size()));List<Object> args=new ArrayList<>(List.of(supplier));args.addAll(chunk);var rows=store.jdbc.queryForList("select * from plm_supplier_color where supplier_code=? and color_id in ("+String.join(",",Collections.nCopies(chunk.size(),"?"))+")",args.toArray());for(var value:snapshots(rows,false)){require(allowInactive||"active".equals(value.get("status")),"供方颜色已停用");result.put(number(value.get("id")),value);}}
        require(result.size()==new HashSet<>(ids).size(),"供方颜色不存在或不属于当前供应商");return result;
    }
    private Map<String,Object> color(String supplier,long id,boolean allowInactive) {
        var rows=store.jdbc.queryForList("select * from plm_supplier_color where supplier_code=? and color_id=?",supplier,id);require(rows.size()==1,"供方颜色不存在或不属于当前供应商");var out=snapshot(rows.get(0),false);require(allowInactive||"active".equals(out.get("status")),"供方颜色已停用");return out;
    }
    private Map<String,Object> range(String supplier,long id,boolean lock) {
        var rows=store.jdbc.queryForList("select * from plm_supplier_range where supplier_code=? and range_id=?"+(lock?" for update":""),supplier,id);require(rows.size()==1,"范围不存在或不属于当前供应商");return snapshot(rows.get(0),true);
    }
    private Map<String,Object> snapshot(Map<String,Object> row,boolean range) {
        return snapshots(List.of(row),range).get(0);
    }
    private List<Map<String,Object>> snapshots(List<Map<String,Object>> rows,boolean range) {
        List<Map<String,Object>> result=new ArrayList<>();for(var row:rows)result.add(rawSnapshot(row,range));decorateImages(result);return result;
    }
    private Map<String,Object> rawSnapshot(Map<String,Object> row,boolean range) {
        var result=decode(store.json,row.get("data"));result.put("id",row.get(range?"range_id":"color_id"));result.put("code",row.get("code"));result.put("supplierCode",row.get("supplier_code"));result.put("name",row.get("name"));result.put("styleKey",row.get("style_key"));result.put("configKey",row.get("config_key"));result.put("status",row.get("status"));result.put("version",row.get("version"));
        result.put(range?"type":"supplierColorCode",row.get(range?"type":"supplier_color_code"));return result;
    }
    private List<Map<String,Object>> findPhones(String value) {
        String sql=value.matches("[0-9]{4}")?"c.code_value=?":"(lower(m.model_name)=lower(?) or lower(b.name||' '||m.model_name)=lower(?) or exists(select 1 from unnest(m.aliases) a where lower(a)=lower(?)))";
        Object[] args=value.matches("[0-9]{4}")?new Object[]{value}:new Object[]{value,value,value};
        var ids=store.jdbc.queryForList("select m.id from plm_phone_model_member m join plm_phone_brand b on b.id=m.brand_id left join plm_code_item c on c.code_item_id=m.code_item_id and c.deleted_flag=0 where "+sql+" order by c.code_value desc nulls last,m.id",Long.class,args);
        return new ArrayList<>(phoneMembers(ids,true).values());
    }
    private Map<Long,Map<String,Object>> phoneMembers(List<Long> ids,boolean allowInactive) {
        Map<Long,Map<String,Object>> result=new LinkedHashMap<>();
        for(int start=0;start<ids.size();start+=100)for(var entry:phones.members(ids.subList(start,Math.min(start+100,ids.size())),allowInactive).entrySet()) {
            var value=new LinkedHashMap<>(entry.getValue());value.put("id",entry.getKey());value.put("phoneModelId",entry.getKey());result.put(entry.getKey(),value);
        }
        var ordered=result.values().stream().sorted(Comparator.<Map<String,Object>,String>comparing(p->text(p.get("phoneModelCode"))).reversed().thenComparing(p->number(p.get("id")),Comparator.reverseOrder())).toList();
        Map<Long,Map<String,Object>> sorted=new LinkedHashMap<>();for(var value:ordered)sorted.put(number(value.get("id")),value);return sorted;
    }
    private List<Map<String,Object>> findColors(String supplier,String value,String style,String config,boolean allowInactive) {
        var rows=store.jdbc.queryForList("select * from plm_supplier_color where supplier_code=? and (code=? or lower(name)=lower(?) or supplier_color_code=?) and (style_key='' or style_key=?) and (config_key='' or config_key=?)"+(allowInactive?"":" and status='active'")+" order by color_id",supplier,value,value,value,style,config);return snapshots(rows,false);
    }
    private List<Map<String,Object>> fileRefs(Object raw){
        require(raw==null||raw instanceof List<?>,"颜色资料引用须为列表");var ids=new LinkedHashSet<Long>();
        for(var f:maps(raw)){long id=number(f.get("id"));require(id>0,"颜色资料引用无效");store.access.requireFile(id);ids.add(id);}
        var images=imageMetadata(ids);require(images.keySet().containsAll(ids),"原色只能引用有权查看的图片，不能采用非图片、受限报价原件或金额资料");
        return ids.stream().map(id->Map.<String,Object>of("id",id)).toList();
    }
    /** Stored references remain IDs; current ACL and authoritative metadata are projected at read time. */
    private void decorateImages(Object value){var ids=new LinkedHashSet<Long>();collectImageRefs(value,ids);projectImageRefs(value,imageMetadata(ids));}
    private void collectImageRefs(Object value,Set<Long> ids){
        if(value instanceof Map<?,?> raw){var row=map(raw);for(var ref:maps(row.get("refs")))if(number(ref.get("id"))>0)ids.add(number(ref.get("id")));for(var entry:row.entrySet())if(!entry.getKey().equals("refs"))collectImageRefs(entry.getValue(),ids);}
        else if(value instanceof List<?> list)for(Object entry:list)collectImageRefs(entry,ids);
    }
    private void projectImageRefs(Object value,Map<Long,Map<String,Object>> images){
        if(value instanceof Map<?,?> raw){var row=map(raw);if(row.containsKey("refs")){var projected=new ArrayList<Map<String,Object>>();var seen=new HashSet<Long>();for(var ref:maps(row.get("refs"))){long id=number(ref.get("id"));if(seen.add(id)&&images.containsKey(id))projected.add(new LinkedHashMap<>(images.get(id)));}row.put("refs",projected);}for(var entry:row.entrySet())if(!entry.getKey().equals("refs"))projectImageRefs(entry.getValue(),images);}
        else if(value instanceof List<?> list)for(Object entry:list)projectImageRefs(entry,images);
    }
    private Map<Long,Map<String,Object>> imageMetadata(Set<Long> ids){
        Map<Long,Map<String,Object>> result=new LinkedHashMap<>();if(ids.isEmpty()||!store.access.hasPermission("file:read"))return result;var values=new ArrayList<>(ids);
        for(int start=0;start<values.size();start+=200){var chunk=values.subList(start,Math.min(start+200,values.size()));
            var images=store.jdbc.queryForList("select a.attachment_id as id,a.file_name as name,a.version_no as version,a.content_type as type,a.content_type as \"contentType\",a.file_size as size from plm_attachment a where a.deleted_flag=0 and lower(coalesce(a.content_type,'')) like 'image/%' and a.attachment_id in ("+String.join(",",Collections.nCopies(chunk.size(),"?"))+") and not exists(select 1 from plm_partner_file f where f.attachment_id=a.attachment_id and f.purpose in ('cooperation_cost','financial')) and not exists(select 1 from plm_partner_file_job j where j.kind='import' and j.source_attachment_id=a.attachment_id) and "+store.access.visibleSqlClause("file","a.attachment_id"),chunk.toArray());
            for(var image:images)result.put(number(image.get("id")),image);
        }return result;
    }
    private static boolean applies(Map<String,Object> value,String style,String config){return (text(value.get("styleKey")).isEmpty()||text(value.get("styleKey")).equals(text(style)))&&(text(value.get("configKey")).isEmpty()||text(value.get("configKey")).equals(text(config)));}
    private static String type(Object value){String kind=text(value);require(Set.of("model","color").contains(kind),"范围类型须为model或color");return kind;}
    private static String bounded(String value,int max){require(value.length()<=max,"字段内容过长");return value;}
    private static List<Map<String,Object>> items(Map<String,Object> input){require(input.get("items") instanceof List<?> && !((List<?>)input.get("items")).isEmpty(),"请列明本次批量资料");for(Object raw:(List<?>)input.get("items"))require(raw instanceof Map<?,?>,"批量资料格式无效");return maps(input.get("items"));}
    private static void checkVersion(Map<String,Object> old,Map<String,Object> item){require(old.isEmpty()?number(item.get("version"))==0:item.containsKey("version")&&number(old.get("version"))==number(item.get("version")),"资料已变化，请刷新核对原值");}
    private static List<Long> ids(Object raw){require(raw==null||raw instanceof List<?>,"成员须为列表");if(raw==null)return List.of();var out=new LinkedHashSet<Long>();for(Object value:(List<?>)raw){Object identity=value instanceof Map<?,?>?map(value).get("id"):value;require(identity instanceof Number,"成员ID须为系统数值；不能把四位型号编码当作成员ID");long id=number(identity);require(id>0,"成员编号无效");out.add(id);}return new ArrayList<>(out);}
    private static List<String> stringList(Object raw){require(raw==null||raw instanceof List<?>,"别名须为列表");if(raw==null)return List.of();var result=new ArrayList<String>();Set<String> seen=new HashSet<>();for(Object value:(List<?>)raw){String s=bounded(required(value,"别名"),255);require(seen.add(s.toLowerCase(Locale.ROOT)),"别名重复");result.add(s);}return result;}
}
