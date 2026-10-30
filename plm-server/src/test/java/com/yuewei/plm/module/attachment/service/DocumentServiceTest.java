package com.yuewei.plm.module.attachment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.attachment.entity.Attachment;
import com.yuewei.plm.module.attachment.repository.AttachmentRepository;
import com.yuewei.plm.module.attachment.vo.AttachmentVO;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentServiceTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final AttachmentRepository files=mock(AttachmentRepository.class);
    final AttachmentService storage=mock(AttachmentService.class);
    final PilotAccessService access=mock(PilotAccessService.class);
    final DocumentService service=new DocumentService(jdbc,files,storage,access,new ObjectMapper().findAndRegisterModules());
    final DocumentService.Reference code=new DocumentService.Reference("source_code",8,"ALL");
    final CurrentUser actor=new CurrentUser(3L,"engineer","真实工程账号",true);
    @BeforeEach void setup(){
        when(access.actor()).thenReturn(actor);when(access.actorId()).thenReturn(3L);
        when(access.access()).thenReturn(new PilotAccessService.Access(actor,"administrator",List.of("*"),true,true));
        when(access.visibleSqlClause(anyString(),anyString())).thenReturn("true");
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(),any())).thenReturn(true);
        when(jdbc.queryForList(anyString(),eq("source_code"),eq(8L))).thenAnswer(i->new ArrayList<>(List.of(new HashMap<>(Map.of("type","source_code","id",8L,"name","共用模具","code","MAB200001")))));
    }
    Attachment file(){var a=new Attachment();a.setAttachmentId(10L);a.setFamilyId(10L);a.setOwnerObjectType("Document");a.setOwnerObjectId(10L);a.setDeletedFlag(0);when(files.selectById(10L)).thenReturn(a);return a;}
    @Test void cadPdfOptionsExcludeExplicitRendersWhileKeepingOriginalScopedManufacturingPdfs(){
        file();var drawing=new Attachment();drawing.setAttachmentId(11L);drawing.setFileExt("pdf");drawing.setFileCategory("drawing");
        var render=new Attachment();render.setAttachmentId(12L);render.setFileExt("pdf");render.setFileCategory("render_3d");
        when(jdbc.queryForList(contains("lower(a.file_ext)='pdf'"),eq(Long.class),eq(10L),eq("document"),eq(10L),eq(10L))).thenReturn(List.of(11L,12L));
        when(files.selectBatchIds(List.of(11L,12L))).thenReturn(List.of(drawing,render));
        assertThat(service.pdfOptions(10L)).extracting(AttachmentVO::getAttachmentId).containsExactly(11L);
        verify(access).requirePermission("file:read");verify(access).requireFile(10L);verify(access).visibleSqlClause("file","a.attachment_id");
    }
    @Test void noObjectOrInvalidPartNeverUploads(){
        assertThatThrownBy(()->service.upload(null,"drawing","V1",null,null,List.of(),null)).hasMessageContaining("至少");
        assertThatThrownBy(()->service.upload(null,"drawing","V1",null,null,List.of(new DocumentService.Reference("sku",8,"PC")),null)).hasMessageContaining("无效");verifyNoInteractions(storage);
    }
    @Test void standaloneUploadUsesStableIdsAndRecordsRealActor(){
        when(storage.uploadDocumentAttachment(isNull(),isNull(),eq("drawing"),eq("V1"),isNull(),isNull())).thenReturn(AttachmentVO.builder().attachmentId(10L).ownerObjectType("Document").ownerObjectId(10L).build());
        var result=service.upload(null,"drawing","V1",null,null,List.of(code,code),null);
        assertThat(result.getOwnerObjectType()).isEqualTo("Document");verify(storage,never()).uploadProjectAttachment(any(),any(),any(),any(),any(),any());
        verify(jdbc).update(contains("insert into plm_document_reference("),eq(10L),eq("source_code"),eq(8L),eq("ALL"),eq("engineer"));
        verify(jdbc).update(contains("reference_history"),eq(10L),eq("[]"),contains("MAB200001"),eq("首次上传关联"),eq(3L),eq("engineer"));
        verify(jdbc,never()).update(contains("plm_collaboration_mold_set"),any(Object[].class));
    }
    @Test void unavailableObjectAndMissingPermissionFailBeforeBinaryStorage(){
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(),any())).thenReturn(false);
        assertThatThrownBy(()->service.upload(null,"drawing","V1",null,null,List.of(code),null)).hasMessageContaining("未获授权");verifyNoInteractions(storage);
        doThrow(new BusinessException(40103,"无写入权限")).when(access).requirePermission("documents:write");
        assertThatThrownBy(()->service.upload(null,"drawing","V1",null,null,List.of(code),null)).hasMessageContaining("权限");verifyNoInteractions(storage);
    }
    @Test void releasedRetrievalReferencesCanChangeWithoutUpdatingPublishedAttachment(){
        var a=file();a.setReleasedAt(java.time.LocalDateTime.now());a.setPublicationReferencesJson("immutable-scope");
        when(jdbc.query(anyString(),any(RowMapper.class),eq(10L))).thenReturn(List.of(code));
        when(jdbc.queryForList(eq("select master_data_status,deleted_flag from plm_product where product_id=? for share"),eq(20L)))
            .thenReturn(List.of(Map.of("master_data_status","active","deleted_flag",0)));
        service.edit(10L,new DocumentService.ReferenceEdit(List.of(code,new DocumentService.Reference("sku",20,"ALL")),List.of(code),"补充生产 SKU",false));
        verify(jdbc).queryForList("select master_data_status,deleted_flag from plm_product where product_id=? for share",20L);
        verify(jdbc).update(contains("reference_history"),eq(10L),anyString(),anyString(),eq("补充生产 SKU"),eq(3L),eq("engineer"));
        verify(files,never()).updateById(any(Attachment.class));assertThat(a.getPublicationReferencesJson()).isEqualTo("immutable-scope");
    }
    @Test void staleAssociationReplacementAndMissingReasonNeverOverwrite(){
        file();when(jdbc.query(anyString(),any(RowMapper.class),eq(10L))).thenReturn(List.of(code));
        assertThatThrownBy(()->service.edit(10L,new DocumentService.ReferenceEdit(List.of(code),List.of(),"纠正",false))).hasMessageContaining("其他人");
        assertThatThrownBy(()->service.edit(10L,new DocumentService.ReferenceEdit(List.of(code),List.of(code),"",false))).hasMessageContaining("原因");
        verify(jdbc,never()).update(contains("delete from"),anyLong());
    }
    @Test void malformedReferenceJsonCannotEnterStorage(){assertThatThrownBy(()->service.parseReferences("not-json")).hasMessageContaining("格式");verifyNoInteractions(storage);}
}
