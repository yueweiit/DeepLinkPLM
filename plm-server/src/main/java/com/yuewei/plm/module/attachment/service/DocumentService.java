package com.yuewei.plm.module.attachment.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.constant.ErrorCodeConstants;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.attachment.entity.Attachment;
import com.yuewei.plm.module.attachment.repository.AttachmentRepository;
import com.yuewei.plm.module.attachment.vo.AttachmentVO;
import com.yuewei.plm.module.pilot.PilotAccessService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** One searchable row per family. Retrieval references never grant access to a binary. */
@Service
@RequiredArgsConstructor
public class DocumentService {
    private final JdbcTemplate jdbc;
    private final AttachmentRepository repository;
    private final AttachmentService storage;
    private final PilotAccessService access;
    private final ObjectMapper json;

    public record Reference(String type, long id, String partType) {
        public Reference { partType = partType == null ? "ALL" : partType; }
    }
    public record ReferenceEdit(List<Reference> references, List<Reference> before, String reason, boolean append) {}
    public record BatchEdit(List<Long> familyIds, List<Reference> references, String reason) {}
    @Data public static class Query {
        private String keyword, fileCategory, status, referenceType, partType, nodeKey, uploader, dateFrom, dateTo;
        private Long referenceId, projectId;
        private int page = 1, size = 50;
    }

    String catalog() {
        // All names/codes are resolved through real object IDs; equal source codes stay separate.
        return """
          catalog as (
            select case when p.product_type='sku' then 'sku' else 'product' end as type,
              p.product_id id, p.product_code code, p.product_name name,
              concat_ws(' / ',nullif(p.model,''),nullif(p.color,''),nullif(p.finished_product_code,'')) subtitle,
              p.parent_product_id parent_id from plm_product p where p.deleted_flag=0 and %s
            union all
            select 'item',i.item_id,coalesce(i.engraved_code,i.phone_model_code,''),
              coalesce(nullif(i.mold_name,''),i.style_name),i.compatible_models,i.product_line_id
              from plm_collaboration_item i where i.deleted_flag=0 and %s
            union all
            select 'mold_set',s.mold_set_id,'MS-'||lpad(s.mold_set_id::text,6,'0'),
              coalesce(nullif(i.mold_name,''),i.style_name)||' / '||s.set_name,
              concat_ws(' / ',i.compatible_models,'第 '||s.set_no||' 套',i.engraved_code,(select string_agg(concat_ws(' ',mp.part_type,mp.original_mold_code,mc.mold_code),' / ') from plm_collaboration_mold_part mp left join plm_product_mold_code mc on mc.product_mold_code_id=mp.product_mold_code_id where mp.mold_set_id=s.mold_set_id)),i.product_line_id
              from plm_collaboration_mold_set s join plm_collaboration_item i on i.item_id=s.item_id
              where s.deleted_flag=0 and i.deleted_flag=0 and %s
            union all
            select 'mold_code',m.product_mold_code_id,m.mold_code,coalesce(m.mold_name,m.mold_code),
              coalesce(m.key_code,''),m.product_id from plm_product_mold_code m where m.deleted_flag=0 and %s
            union all
            select 'source_code',s.source_code_id,s.mold_code,s.mold_name,
              concat_ws(' / ',case when s.source_system='erp' then 'DeepLinkERP' else '钉钉' end,
                s.part_type,s.specifications,case when s.source_disabled then '已停用' end),null::bigint
              from plm_mold_code_source s where %s
            union all
            select 'partner',p.record_id,p.code,p.name,p.kind,null::bigint
              from plm_partner_record p where p.deleted_flag=0 and %s
          )
          """.formatted(access.visibleSqlClause("product","p.product_id"),
            access.visibleSqlClause("item","i.item_id"),access.visibleSqlClause("mold","s.mold_set_id"),
            access.visibleSqlClause("product","m.product_id"),access.access().allObjects() ? "true" : "false",access.visibleSqlClause("partner","p.record_id"));
    }

    static final String REFERENCES = """
      refs as (
        select family_id,object_type,object_id,part_type from plm_document_reference
        union
        select distinct coalesce(a.family_id,a.attachment_id),case when p.product_type='sku' then 'sku' else 'product' end,
          p.product_id,'ALL' from plm_attachment a join plm_product p on p.product_id=a.owner_object_id
          where a.deleted_flag=0 and p.deleted_flag=0 and lower(a.owner_object_type) in ('product','project')
          and not exists(select 1 from plm_document_reference r where r.family_id=coalesce(a.family_id,a.attachment_id))
      )
      """;

    private String families() {
        return "with " + catalog() + "," + REFERENCES + "," + """
          visible as (
            select a.*,coalesce(a.family_id,a.attachment_id) doc_family,
              row_number() over(partition by coalesce(a.family_id,a.attachment_id) order by a.created_at desc,a.attachment_id desc) rn
              from plm_attachment a where a.deleted_flag=0 and lower(a.owner_object_type) in ('product','project','document') and %s
          ), families as (
            select v.*, (select count(*) from visible r where r.doc_family=v.doc_family) version_count,
              (select r.attachment_id from visible r where r.doc_family=v.doc_family and r.released_at is not null
                order by r.released_at desc,r.attachment_id desc limit 1) formal_id,
              coalesce((select max(h.changed_at) from plm_document_reference_history h where h.family_id=v.doc_family),v.created_at) reference_updated_at,
              (select h.actor_name from plm_document_reference_history h where h.family_id=v.doc_family order by h.changed_at desc,h.history_id desc limit 1) reference_updated_by
              from visible v where v.rn=1
          )
          """.formatted(access.visibleSqlClause("file","a.attachment_id"));
    }

    private String filters(Query q, List<Object> args) {
        List<String> filters = new ArrayList<>();
        if (text(q.keyword)) {
            filters.add("(exists(select 1 from visible v where v.doc_family=f.doc_family and concat_ws(' ',v.original_file_name,v.remark) ilike ?) or exists(select 1 from refs r join catalog c on c.type=r.object_type and c.id=r.object_id where r.family_id=f.doc_family and concat_ws(' ',c.name,c.code,c.subtitle) ilike ?))");
            args.add("%"+q.keyword.trim()+"%"); args.add("%"+q.keyword.trim()+"%");
        }
        if (text(q.fileCategory)) { filters.add("f.file_category=?"); args.add(q.fileCategory); }
        if (q.referenceId != null && text(q.referenceType)) {
            filters.add("exists(select 1 from refs r join catalog c on c.type=r.object_type and c.id=r.object_id where r.family_id=f.doc_family and r.object_type=? and r.object_id=?" + (text(q.partType)?" and (r.part_type=? or r.part_type='ALL' or ?='ALL')":"")+")");
            args.add(q.referenceType); args.add(q.referenceId); if(text(q.partType)){args.add(q.partType);args.add(q.partType);}
        }
        if (q.projectId != null) {
            access.requireProduct(q.projectId);
            filters.add("exists(select 1 from refs r join catalog c on c.type=r.object_type and c.id=r.object_id where r.family_id=f.doc_family and ((r.object_type='product' and r.object_id=?) or (r.object_type='item' and "+com.yuewei.plm.module.project.ProjectBusinessLinks.itemExists("r.object_id")+") or (r.object_type='mold_set' and "+com.yuewei.plm.module.project.ProjectBusinessLinks.setExists("r.object_id")+")))");
            args.add(q.projectId);args.add(q.projectId);args.add(q.projectId);
        }
        if (text(q.nodeKey)) { filters.add("exists(select 1 from visible v where v.doc_family=f.doc_family and v.timeline_node_key=?)"); args.add(q.nodeKey); }
        if (text(q.uploader)) { filters.add("f.created_by ilike ?"); args.add("%"+q.uploader.trim()+"%"); }
        if (text(q.dateFrom)) { filters.add("f.created_at>=?::date"); args.add(validDate(q.dateFrom)); }
        if (text(q.dateTo)) { filters.add("f.created_at<?::date+interval '1 day'"); args.add(validDate(q.dateTo)); }
        return filters.isEmpty()?"true":String.join(" and ",filters);
    }
    private String status(String status) {
        return switch(Objects.toString(status,"all")) {
            case "all","" -> "true";
            case "released" -> "f.formal_id is not null";
            case "draft" -> "f.released_at is null";
            case "unlinked" -> "not exists(select 1 from refs r where r.family_id=f.doc_family and r.object_type in ('product','sku','item','mold_code','source_code','mold_set','partner'))";
            default -> throw invalid("文件状态不支持");
        };
    }
    public Map<String,Object> page(Query q) {
        access.requirePermission("file:read");
        int size=Math.max(1,Math.min(q.size,100)),page=Math.max(1,q.page);
        List<Object> args=new ArrayList<>(); String where=filters(q,args),cte=families();
        Map<String,Object> counts=jdbc.queryForMap(cte+"select count(*) as \"all\",count(*) filter(where formal_id is not null) as released,count(*) filter(where released_at is null) as draft,count(*) filter(where "+status("unlinked")+") as unlinked from families f where "+where,args.toArray());
        List<Object> paged=new ArrayList<>(args); paged.add(size); paged.add((long)(page-1)*size);
        var rows=jdbc.queryForList(cte+"select f.doc_family,f.attachment_id,f.formal_id,f.version_count,f.reference_updated_at,greatest(f.created_at,f.updated_at,f.reference_updated_at) doc_updated_at,case when f.reference_updated_at>coalesce(f.updated_at,f.created_at) then f.reference_updated_by else coalesce(f.updated_by,f.created_by) end doc_updated_by from families f where "+where+" and "+status(q.status)+" order by greatest(f.created_at,f.updated_at,f.reference_updated_at) desc,f.attachment_id desc limit ? offset ?",paged.toArray());
        long total=((Number)counts.get(Objects.toString(q.status,"all").isBlank()?"all":Objects.toString(q.status,"all"))).longValue();
        var ids=rows.stream().flatMap(r->java.util.stream.Stream.of(r.get("attachment_id"),r.get("formal_id"))).filter(Objects::nonNull).map(v->((Number)v).longValue()).distinct().toList();
        Map<Long,AttachmentVO> files=new HashMap<>();
        if(!ids.isEmpty())repository.selectBatchIds(ids).forEach(a->files.put(a.getAttachmentId(),AttachmentVO.from(a)));
        var familyIds=rows.stream().map(r->((Number)r.get("doc_family")).longValue()).toList();
        var allRefs=referenceViews(familyIds);
        List<Map<String,Object>> content=new ArrayList<>();
        for(var row:rows){long family=((Number)row.get("doc_family")).longValue();var value=new LinkedHashMap<String,Object>();
            value.put("familyId",family);value.put("latestVersion",files.get(((Number)row.get("attachment_id")).longValue()));
            value.put("formalVersion",row.get("formal_id")==null?null:files.get(((Number)row.get("formal_id")).longValue()));
            value.put("versionCount",row.get("version_count"));value.put("references",allRefs.getOrDefault(family,List.of()));
            value.put("referenceUpdatedAt",row.get("reference_updated_at"));value.put("updatedAt",row.get("doc_updated_at"));value.put("updatedBy",row.get("doc_updated_by"));content.add(value);
        }
        var result=new LinkedHashMap<String,Object>();result.put("content",content);result.put("page",page);result.put("size",size);
        result.put("totalElements",total);result.put("totalPages",(total+size-1)/size);result.put("counts",counts);return result;
    }

    public List<Map<String,Object>> options(String type, String keyword, Long parentId) {
        access.requirePermission("file:read");
        if(type!=null&&!Set.of("product","sku","item","mold_code","source_code","mold_set","project").contains(type))throw invalid("关联对象类型不支持");
        List<Object> args=new ArrayList<>();List<String> conditions=new ArrayList<>();conditions.add("(type<>'source_code' or exists(select 1 from plm_mold_code_source s where s.source_code_id=id and not s.source_disabled))");
        if(text(type)){conditions.add("type=?");args.add(type.equals("project")?"product":type);}
        if(text(keyword)){conditions.add("concat_ws(' ',code,name,subtitle) ilike ?");args.add("%"+keyword.trim()+"%");}
        if(parentId!=null){conditions.add("(parent_id=? or (type='product' and id=?))");args.add(parentId);args.add(parentId);}
        return jdbc.queryForList("with "+catalog()+" select type,id,'ALL' as \"partType\",code,name,subtitle,parent_id as \"parentId\" from catalog "+(conditions.isEmpty()?"":"where "+String.join(" and ",conditions))+" order by type,code,id limit 100",args.toArray());
    }

    private Map<Long,List<Map<String,Object>>> referenceViews(List<Long> ids) {
        Map<Long,List<Map<String,Object>>> result=new HashMap<>();if(ids.isEmpty())return result;
        String marks=String.join(",",Collections.nCopies(ids.size(),"?"));
        var rows=jdbc.queryForList("with "+catalog()+","+REFERENCES+" select r.family_id,c.type,c.id,r.part_type as \"partType\",c.code,c.name,c.subtitle from refs r join catalog c on c.type=r.object_type and c.id=r.object_id where r.family_id in ("+marks+") order by c.type,c.code,c.id,r.part_type",ids.toArray());
        for(var row:rows){long family=((Number)row.remove("family_id")).longValue();result.computeIfAbsent(family,k->new ArrayList<>()).add(row);}return result;
    }
    private Attachment file(long id){access.requireFile(id);var a=repository.selectById(id);if(a==null||Integer.valueOf(1).equals(a.getDeletedFlag()))throw invalid("文件不存在");return a;}
    private long family(long id){var a=file(id);return a.getFamilyId()==null?id:a.getFamilyId();}
    public List<Map<String,Object>> references(long id){return referenceViews(List.of(family(id))).values().stream().findFirst().orElse(List.of());}
    private List<Reference> rawReferences(long root){return jdbc.query("with "+REFERENCES+" select object_type,object_id,part_type from refs where family_id=? order by object_type,object_id,part_type",(rs,n)->new Reference(rs.getString(1),rs.getLong(2),rs.getString(3)),root);}

    private List<Reference> validate(List<Reference> input) {
        if(input==null||input.isEmpty()||input.size()>300)throw invalid("至少关联一个对象，最多 300 个");
        var refs=new ArrayList<>(new LinkedHashSet<>(input));
        for(var ref:refs){
            if(ref==null||ref.type==null||ref.id<1||!Set.of("product","sku","item","mold_code","source_code","mold_set","partner").contains(ref.type)||!Set.of("ALL","PC","TPU").contains(ref.partType)||(!ref.type.equals("mold_set")&&!ref.partType.equals("ALL")))throw invalid("关联对象或部件范围无效");
            if(!Boolean.TRUE.equals(jdbc.queryForObject("with "+catalog()+" select exists(select 1 from catalog c where type=? and id=? and (type<>'source_code' or exists(select 1 from plm_mold_code_source s where s.source_code_id=c.id and not s.source_disabled)))",Boolean.class,ref.type,ref.id)))throw invalid("关联对象不存在、已停用或未获授权");
        }return refs;
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw invalid("关联资料无法编码");}}
    private void storeReferences(long root,List<Reference> refs,List<Reference> before,String reason) {
        // Existing retrieval associations stay readable/correctable after retirement. A new
        // association takes a shared master lock until the family transaction commits.
        for(var ref:refs.stream().filter(r->!before.contains(r)).sorted(Comparator.comparing(Reference::type).thenComparingLong(Reference::id)).toList()){
            String kind=switch(ref.type){case "product","sku"->"product";case "item"->"item";case "mold_set"->"mold_set";default->null;};
            if(kind!=null)com.yuewei.plm.module.masterdata.ProductMoldLifecycleService.requireActive(jdbc,kind,ref.id);
        }
        jdbc.update("delete from plm_document_reference where family_id=?",root);
        String actor=access.actor().username();
        for(var r:refs){jdbc.update("insert into plm_document_reference(family_id,object_type,object_id,part_type,created_by) values(?,?,?,?,?)",root,r.type,r.id,r.partType,actor);if(r.type.equals("partner"))jdbc.update("insert into plm_partner_file(record_id,attachment_id) values(?,?) on conflict do nothing",r.id,root);}
        jdbc.update("insert into plm_document_reference_history(family_id,before_references,after_references,reason,actor_id,actor_name) values(?,?::jsonb,?::jsonb,?,?,?)",root,encode(decorate(before)),encode(decorate(refs)),reason,access.actorId(),actor);
    }
    private List<Map<String,Object>> decorate(List<Reference> refs) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(var r:refs){var found=jdbc.queryForList("with "+catalog()+" select type,id,code,name,subtitle from catalog where type=? and id=?",r.type,r.id);var row=found.isEmpty()?new LinkedHashMap<String,Object>(Map.of("type",r.type,"id",r.id,"name","已归档对象")):new LinkedHashMap<>(found.get(0));row.put("partType",r.partType);result.add(row);}
        return result;
    }
    @Transactional public AttachmentVO upload(MultipartFile file,String category,String version,String remark,Long projectId,List<Reference> references,HttpServletRequest request) {
        access.requirePermission("documents:write");var refs=validate(references);
        if(version!=null&&(version.isBlank()||version.length()>32))throw invalid("版本编号须为 1 到 32 个字符");
        if(projectId!=null)access.requireProduct(projectId);
        var created=projectId==null?storage.uploadDocumentAttachment(null,file,category,version==null?"V1":version.trim(),remark,request):storage.uploadProjectAttachment(projectId,file,category,version==null?"V1":version.trim(),remark,request);
        storeReferences(created.getAttachmentId(),refs,List.of(),"首次上传关联");
        // A creator can read their own upload; referenced objects do not grant any other reader access.
        if(!access.access().allObjects())jdbc.update("insert into plm_pilot_scope(user_id,entity_type,entity_id) values(?,'file',?) on conflict do nothing",access.actorId(),created.getAttachmentId());
        return created;
    }
    /** Only the verified disk-backed transfer path bypasses the ordinary fixed upload-size policy. */
    @Transactional public AttachmentVO uploadResumable(MultipartFile file,String category,String version,String remark,List<Reference> references,HttpServletRequest request) {
        access.requirePermission("documents:write");var refs=validate(references);
        var created=storage.uploadResumableDocumentAttachment(file,category,version,remark,request);
        storeReferences(created.getAttachmentId(),refs,List.of(),"分片上传完成关联");
        if(!access.access().allObjects())jdbc.update("insert into plm_pilot_scope(user_id,entity_type,entity_id) values(?,'file',?) on conflict do nothing",access.actorId(),created.getAttachmentId());
        return created;
    }
    public List<Reference> parseReferences(String value){try{return json.readValue(value,new TypeReference<List<Reference>>(){});}catch(Exception e){throw invalid("关联对象格式无效");}}
    @Transactional public List<Map<String,Object>> edit(long id,ReferenceEdit dto) {
        access.requirePermission("documents:write");long root=family(id);
        jdbc.queryForList("select attachment_id from plm_attachment where attachment_id=? for update",root);
        if(!text(dto.reason))throw invalid("关联修改原因必填");
        var before=rawReferences(root);
        // Editing requires access to every existing target, preventing invisible references being erased.
        if(!before.isEmpty()&&!access.access().allObjects())validate(before);
        if(!dto.append && (dto.before==null||!new HashSet<>(before).equals(new HashSet<>(dto.before))))throw new BusinessException(40901,"关联已由其他人修改，请刷新后核对");
        var requested=new ArrayList<>(dto.references==null?List.<Reference>of():dto.references);if(dto.append)requested.addAll(before);
        var next=validate(requested);storeReferences(root,next,before,dto.reason.trim());return references(id);
    }
    @Transactional public void batch(BatchEdit dto) {
        if(dto.familyIds==null||dto.familyIds.isEmpty()||dto.familyIds.size()>100)throw invalid("请选择 1 到 100 份文件");
        for(long id:dto.familyIds.stream().distinct().sorted().toList())edit(id,new ReferenceEdit(dto.references,null,dto.reason,true));
    }
    public List<Map<String,Object>> history(long id) {
        long root=family(id);
        return jdbc.queryForList("select history_id as \"historyId\",before_references::text as \"beforeReferences\",after_references::text as \"afterReferences\",reason,actor_name as \"actorName\",changed_at as \"changedAt\" from plm_document_reference_history where family_id=? order by history_id desc",root);
    }
    public List<AttachmentVO> pdfOptions(long id) {
        access.requirePermission("file:read");var source=file(id);long root=source.getFamilyId()==null?id:source.getFamilyId();
        var ids=jdbc.queryForList("with "+REFERENCES+" select a.attachment_id from plm_attachment a where a.deleted_flag=0 and lower(a.file_ext)='pdf' and a.attachment_id<>? and "+access.visibleSqlClause("file","a.attachment_id")+" and ((lower(a.owner_object_type) in ('product','project') and ? in ('product','project') and a.owner_object_id=?) or exists(select 1 from refs r join refs p on r.object_type=p.object_type and r.object_id=p.object_id and (r.part_type=p.part_type or r.part_type='ALL' or p.part_type='ALL') where r.family_id=? and p.family_id=coalesce(a.family_id,a.attachment_id))) order by a.created_at desc,a.attachment_id desc",Long.class,id,source.getOwnerObjectType().toLowerCase(Locale.ROOT),source.getOwnerObjectId(),root);
        return ids.isEmpty()?List.of():repository.selectBatchIds(ids).stream().filter(pdf->!"render_3d".equals(pdf.getFileCategory())).map(AttachmentVO::from).toList();
    }
    public List<Map<String,Object>> nodes(long projectId){access.requirePermission("file:read");access.requireProduct(projectId);return jdbc.queryForList("select distinct timeline_node_key as key from plm_attachment where deleted_flag=0 and lower(owner_object_type) in ('product','project') and owner_object_id=? and timeline_node_key is not null order by timeline_node_key",projectId);}
    private static String validDate(String date){try{return java.time.LocalDate.parse(date).toString();}catch(Exception e){throw invalid("日期格式无效");}}
    private static boolean text(String value){return value!=null&&!value.isBlank();}
    private static BusinessException invalid(String message){return new BusinessException(ErrorCodeConstants.VALIDATION_ERROR,message);}
}
