package com.yuewei.plm.module.attachment.service;

import com.yuewei.plm.common.constant.ErrorCodeConstants;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.attachment.repository.AttachmentRepository;
import com.yuewei.plm.module.attachment.entity.Attachment;
import com.yuewei.plm.module.attachment.vo.AttachmentVO;
import com.yuewei.plm.module.pilot.PilotAccessService;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Lineage and publication metadata. Binary upload/download remain in AttachmentService. */
@Service @RequiredArgsConstructor
public class AttachmentVersionService {
    private final AttachmentService attachments;private final AttachmentRepository repository;
    private final JdbcTemplate jdbc;private final PilotAccessService access;
    private final OperationLogService logs;private final ObjectMapper json;
    public record Publication(String reason,Long itemId,Long moldSetId,Long pdfAttachmentId,List<DocumentService.Reference> references) {
        public Publication(String reason,Long itemId,Long moldSetId,Long pdfAttachmentId) { this(reason,itemId,moldSetId,pdfAttachmentId,null); }
    }
    private Attachment file(long id){access.requireFile(id);Attachment a=repository.selectById(id);if(a==null||Integer.valueOf(1).equals(a.getDeletedFlag()))throw new BusinessException(ErrorCodeConstants.RESOURCE_NOT_FOUND,"文件不存在");return a;}
    public List<AttachmentVO> versions(long id) {
        Attachment a=file(id);long root=a.getFamilyId()==null?id:a.getFamilyId();
        // One batch selects every revision, including superseded releases.
        var ids=jdbc.queryForList("select a.attachment_id from plm_attachment a where deleted_flag=0 and (attachment_id=? or family_id=?) and "+access.visibleSqlClause("file","a.attachment_id")+" order by created_at desc,attachment_id desc",Long.class,root,root);
        if(ids.isEmpty())return List.of();
        return repository.selectBatchIds(ids).stream()
            .sorted(Comparator.comparing(Attachment::getCreatedAt,Comparator.nullsLast(Comparator.reverseOrder()))).map(AttachmentVO::from).toList();
    }
    private boolean sameSnapshot(String value,List<DocumentService.Reference> refs) {
        try {Set<DocumentService.Reference> stored=new HashSet<>();for(var r:json.readTree(value==null?"[]":value))stored.add(new DocumentService.Reference(r.path("type").asText(),r.path("id").asLong(),r.path("partType").asText("ALL")));return stored.equals(new HashSet<>(refs));}
        catch(Exception e){return false;}
    }
    @Transactional public AttachmentVO upload(long previous,MultipartFile upload,String version,String reason,HttpServletRequest request) {
        access.requirePermission("documents:write");Attachment old=file(previous);
        if(version==null||version.isBlank()||version.length()>32||reason==null||reason.isBlank())throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR,"新版本编号和修改原因必填");
        long root=old.getFamilyId()==null?previous:old.getFamilyId();
        jdbc.queryForList("select attachment_id from plm_attachment where attachment_id=? for update",root);
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from plm_attachment where deleted_flag=0 and version_no=? and (attachment_id=? or family_id=?))",Boolean.class,version.trim(),root,root)))throw new BusinessException(ErrorCodeConstants.VERSION_FROZEN,"同一图纸不能重复使用版本编号");
        var created="Document".equals(old.getOwnerObjectType())
            ? attachments.uploadDocumentAttachment(root,upload,old.getFileCategory(),version.trim(),reason,request)
            : attachments.uploadProjectAttachment(old.getOwnerObjectId(),upload,old.getFileCategory(),version.trim(),reason,request);
        jdbc.update("update plm_attachment set family_id=?,change_reason=?,collaboration_item_id=?,mold_set_id=? where attachment_id=?",root,reason.trim(),old.getCollaborationItemId(),old.getMoldSetId(),created.getAttachmentId());
        jdbc.update("insert into plm_pilot_scope(user_id,entity_type,entity_id) select user_id,'file',? from plm_pilot_scope where entity_type='file' and entity_id=? on conflict do nothing",created.getAttachmentId(),previous);
        jdbc.update("insert into plm_partner_file(record_id,attachment_id,purpose) select record_id,?,purpose from plm_partner_file where attachment_id=? on conflict do nothing",created.getAttachmentId(),previous);
        return AttachmentVO.from(repository.selectById(created.getAttachmentId()));
    }
    @Transactional public AttachmentVO publish(long id,Publication dto,HttpServletRequest request) {
        access.requirePermission("documents:publish");access.requireFile(id);
        var initial=file(id);jdbc.queryForList("select attachment_id from plm_attachment where attachment_id=? for update",initial.getFamilyId()==null?id:initial.getFamilyId());
        Attachment a=file(id);
        if(dto.reason()==null||dto.reason().isBlank())throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR,"发布说明必填");
        if(a.getReleasedAt()!=null){if(dto.references()!=null&&!sameSnapshot(a.getPublicationReferencesJson(),dto.references()))throw new BusinessException(ErrorCodeConstants.VERSION_FROZEN,"正式发布范围已固定，请上传新版本");if(!Objects.equals(a.getChangeReason(),dto.reason().trim())||!Objects.equals(a.getCollaborationItemId(),dto.itemId())||!Objects.equals(a.getMoldSetId(),dto.moldSetId())||!Objects.equals(a.getDerivedPdfAttachmentId(),dto.pdfAttachmentId()))throw new BusinessException(ErrorCodeConstants.VERSION_FROZEN,"正式发布内容已固定，请上传新版本");return AttachmentVO.from(a);}
        if(dto.itemId()!=null){access.requireItem(dto.itemId());Boolean valid=jdbc.queryForObject("select exists(select 1 from plm_collaboration_item i where i.item_id=? and (i.product_line_id=? or exists(select 1 from plm_collaboration_item_project p where p.item_id=i.item_id and p.project_id=?)))",Boolean.class,dto.itemId(),a.getOwnerObjectId(),a.getOwnerObjectId());if(!Boolean.TRUE.equals(valid))throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR,"图纸所属项目与协作档案不一致");}
        if(dto.moldSetId()!=null){access.requireMold(dto.moldSetId());Boolean valid=jdbc.queryForObject("select exists(select 1 from plm_collaboration_mold_set where mold_set_id=? and item_id=?)",Boolean.class,dto.moldSetId(),dto.itemId());if(!Boolean.TRUE.equals(valid))throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR,"模具必须属于所选协作档案");}
        if(Set.of("step","stp","iges","igs","sldprt","sldasm","dwg","dxf","prt","3dm","x_t","x_b","stl","obj").contains(Objects.toString(a.getFileExt(),"").toLowerCase(Locale.ROOT))&&dto.pdfAttachmentId()==null)throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR,"CAD/3D 原文件发布时须显式关联同项目 PDF 衍生件；不提供在线3D渲染");
        if(dto.pdfAttachmentId()!=null){Attachment pdf=file(dto.pdfAttachmentId());boolean sameProject=!"Document".equals(a.getOwnerObjectType())&&!"Document".equals(pdf.getOwnerObjectType())&&a.getOwnerObjectId().equals(pdf.getOwnerObjectId());
            long root=a.getFamilyId()==null?id:a.getFamilyId(),pdfRoot=pdf.getFamilyId()==null?pdf.getAttachmentId():pdf.getFamilyId();
            boolean shared=Boolean.TRUE.equals(jdbc.queryForObject("with "+DocumentService.REFERENCES+" select exists(select 1 from refs r join refs p on r.object_type=p.object_type and r.object_id=p.object_id and (r.part_type=p.part_type or r.part_type='ALL' or p.part_type='ALL') where r.family_id=? and p.family_id=?)",Boolean.class,root,pdfRoot));
            if("render_3d".equals(pdf.getFileCategory()))throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR,"3D效果图不能作为CAD源文件的正式PDF衍生件，请采用同项目的制造图纸PDF");
            if(!"pdf".equalsIgnoreCase(pdf.getFileExt())||(!sameProject&&!shared)||id==dto.pdfAttachmentId())throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR,"衍生文件必须是同项目或共享明确关联对象的另一份 PDF");}
        long root=a.getFamilyId()==null?id:a.getFamilyId();
        var snapshot=jdbc.queryForList("select r.object_type as type,r.object_id as id,r.part_type as \"partType\",coalesce(p.product_code,c.mold_code,src.mold_code,i.engraved_code,'MS-'||lpad(ms.mold_set_id::text,6,'0'),'') as code,coalesce(p.product_name,c.mold_name,src.mold_name,i.mold_name,i.style_name,ms.set_name,'') as name from plm_document_reference r left join plm_product p on r.object_type in ('product','sku') and p.product_id=r.object_id left join plm_product_mold_code c on r.object_type='mold_code' and c.product_mold_code_id=r.object_id left join plm_mold_code_source src on r.object_type='source_code' and src.source_code_id=r.object_id left join plm_collaboration_item i on r.object_type='item' and i.item_id=r.object_id left join plm_collaboration_mold_set ms on r.object_type='mold_set' and ms.mold_set_id=r.object_id where r.family_id=? order by r.object_type,r.object_id,r.part_type",root);
        if(snapshot.isEmpty()&&!"Document".equals(a.getOwnerObjectType())) {
            var owner=jdbc.queryForList("select case when product_type='sku' then 'sku' else 'product' end as type,product_id as id,'ALL' as \"partType\",product_code as code,product_name as name from plm_product where product_id=? and deleted_flag=0",a.getOwnerObjectId());snapshot.addAll(owner);
        }
        if(dto.references()!=null) {
            var actual=snapshot.stream().map(r->new DocumentService.Reference(r.get("type").toString(),((Number)r.get("id")).longValue(),r.get("partType").toString())).collect(java.util.stream.Collectors.toSet());
            if(!actual.equals(new HashSet<>(dto.references())))throw new BusinessException(40901,"关联范围已变化，请刷新后重新核对发布");
        }
        try {a.setPublicationReferencesJson(json.writeValueAsString(snapshot));} catch(Exception e) {throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR,"发布范围无法编码");}
        a.setFamilyId(a.getFamilyId()==null?id:a.getFamilyId());a.setChangeReason(dto.reason().trim());a.setStatus("released");a.setReleasedAt(LocalDateTime.now());a.setReleasedBy(access.actor().username());a.setReleasedByUserId(access.actorId());
        a.setCollaborationItemId(dto.itemId());a.setMoldSetId(dto.moldSetId());a.setDerivedPdfAttachmentId(dto.pdfAttachmentId());a.setUpdatedBy(access.actor().username());a.setUpdatedAt(java.time.LocalDateTime.now());repository.updateById(a);
        try {logs.logSuccess(OperationLogCreateCommand.builder().action("DOCUMENT_RELEASE").businessType("ATTACHMENT").businessId(Long.toString(id)).detailJson(json.writeValueAsString(Map.of("familyId",a.getFamilyId(),"versionNo",Objects.toString(a.getVersionNo(),""),"reason",dto.reason().trim(),"actorId",access.actorId()))).request(request).build());}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR,"发布审计无法编码");}
        return AttachmentVO.from(a);
    }
}
