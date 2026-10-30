package com.yuewei.plm.module.attachment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.attachment.entity.Attachment;
import com.yuewei.plm.module.attachment.repository.AttachmentRepository;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AttachmentVersionServiceTest {
    final AttachmentService storage=mock(AttachmentService.class);
    final AttachmentRepository files=mock(AttachmentRepository.class);
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final PilotAccessService access=mock(PilotAccessService.class);
    final OperationLogService logs=mock(OperationLogService.class);
    final AttachmentVersionService service=new AttachmentVersionService(storage,files,jdbc,access,logs,new ObjectMapper().findAndRegisterModules());
    Attachment file(long id,String ext){var a=new Attachment();a.setAttachmentId(id);a.setOwnerObjectId(10L);a.setOwnerObjectType("Product");a.setFileExt(ext);a.setVersionNo("V1");a.setDeletedFlag(0);a.setStatus("draft");a.setCreatedAt(LocalDateTime.now());when(files.selectById(id)).thenReturn(a);return a;}
    void actor(){when(access.actor()).thenReturn(new CurrentUser(3L,"engineer","真实工程账号",false));when(access.actorId()).thenReturn(3L);}
    @Test void renderRevisionKeepsItsPurposeAndFamilyThroughTheExistingVersionWriter(){
        var old=file(1,"png");old.setFamilyId(1L);old.setFileCategory("render_3d");
        var created=file(2,"pdf");created.setFamilyId(1L);created.setFileCategory("render_3d");created.setVersionNo("V2");
        when(storage.uploadProjectAttachment(eq(10L),isNull(),eq("render_3d"),eq("V2"),eq("更新效果图标注"),isNull())).thenReturn(com.yuewei.plm.module.attachment.vo.AttachmentVO.from(created));
        var result=service.upload(1L,null,"V2","更新效果图标注",null);
        assertThat(result.getFileCategory()).isEqualTo("render_3d");assertThat(result.getFamilyId()).isEqualTo(1L);assertThat(result.getVersionNo()).isEqualTo("V2");
        assertThat(old.getVersionNo()).isEqualTo("V1");assertThat(old.getFileCategory()).isEqualTo("render_3d");verify(access).requirePermission("documents:write");verify(access).requireFile(1L);
    }
    @Test void aRenderPdfCanPublishAsItselfButCannotBeBoundAsACadManufacturingDerivative(){
        actor();var cad=file(1,"step");cad.setFileCategory("drawing");var render=file(2,"pdf");render.setFileCategory("render_3d");
        assertThat(service.publish(2L,new AttachmentVersionService.Publication("效果图版本确认",null,null,null),null).getFileCategory()).isEqualTo("render_3d");
        assertThatThrownBy(()->service.publish(1L,new AttachmentVersionService.Publication("源文件正式发布",null,null,2L),null)).hasMessageContaining("3D效果图不能作为CAD源文件");
        verify(files,never()).updateById(cad);assertThat(cad.getDerivedPdfAttachmentId()).isNull();assertThat(cad.getStatus()).isEqualTo("draft");
    }
    @Test void publicationRetainsImmutableVersionAndRealPublisherWithoutCopyingBinary() {
        actor();var a=file(1,"pdf");var dto=new AttachmentVersionService.Publication("完成核对",null,null,null);
        assertThat(service.publish(1,dto,null).getReleasedBy()).isEqualTo("engineer");
        assertThat(a.getReleasedByUserId()).isEqualTo(3L);assertThat(a.getFamilyId()).isEqualTo(1L);
        assertThat(service.publish(1,dto,null).getReleasedAt()).isEqualTo(a.getReleasedAt());
        assertThatThrownBy(()->service.publish(1,new AttachmentVersionService.Publication("覆盖历史",null,null,null),null)).isInstanceOf(BusinessException.class).hasMessageContaining("固定");
        verify(files,times(1)).updateById(a);verify(logs,times(1)).logSuccess(any());verifyNoInteractions(storage);
        verify(jdbc,times(3)).queryForList(contains("for update"),eq(1L));
    }
    @Test void cadRequiresAnExplicitPdfAndRejectsAnotherProjectsDerivative() {
        actor();file(1,"step");var pdf=file(2,"pdf");
        assertThatThrownBy(()->service.publish(1,new AttachmentVersionService.Publication("确认",null,null,null),null)).hasMessageContaining("PDF");
        pdf.setOwnerObjectId(20L);
        assertThatThrownBy(()->service.publish(1,new AttachmentVersionService.Publication("确认",null,null,2L),null)).hasMessageContaining("同项目");
        verify(files,never()).updateById(any(Attachment.class));
        pdf.setOwnerObjectId(10L);assertThat(service.publish(1,new AttachmentVersionService.Publication("确认",null,null,2L),null).getDerivedPdfAttachmentId()).isEqualTo(2L);
    }
    @Test void authorizationAndAssociationValidationRunBeforePublication() {
        file(1,"pdf");doThrow(new BusinessException(40103,"无发布权限")).when(access).requirePermission("documents:publish");
        assertThatThrownBy(()->service.publish(1,new AttachmentVersionService.Publication("确认",null,null,null),null)).hasMessageContaining("权限");verifyNoInteractions(jdbc,logs);verify(files,never()).selectById(anyLong());
        doNothing().when(access).requirePermission("documents:publish");
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(),any(),any())).thenReturn(false);
        assertThatThrownBy(()->service.publish(1,new AttachmentVersionService.Publication("确认",99L,null,null),null)).hasMessageContaining("不一致");verify(files,never()).updateById(any(Attachment.class));
    }
    @Test void versionHistoryUsesOneScopedBatchAndDuplicateVersionNeverUploads() {
        var a=file(1,"pdf");when(access.visibleSqlClause("file","a.attachment_id")).thenReturn("false");
        when(jdbc.queryForList(anyString(),eq(Long.class),eq(1L),eq(1L))).thenReturn(List.of(1L,2L));
        var b=file(2,"pdf");when(files.selectBatchIds(List.of(1L,2L))).thenReturn(List.of(a,b));
        assertThat(service.versions(1)).hasSize(2);verify(files).selectBatchIds(List.of(1L,2L));
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq("V1"),eq(1L),eq(1L))).thenReturn(true);
        assertThatThrownBy(()->service.upload(1,null,"V1","更正",null)).hasMessageContaining("重复");verifyNoInteractions(storage);
    }
    @Test void standaloneRevisionKeepsFamilyWithoutRequiringAProductProject() {
        var old=file(1,"step");old.setOwnerObjectType("Document");old.setOwnerObjectId(1L);old.setFamilyId(1L);
        var created=file(2,"step");created.setOwnerObjectType("Document");created.setOwnerObjectId(1L);created.setFamilyId(1L);
        when(storage.uploadDocumentAttachment(eq(1L),isNull(),eq(old.getFileCategory()),eq("V2"),eq("修改结构"),isNull())).thenReturn(com.yuewei.plm.module.attachment.vo.AttachmentVO.from(created));
        assertThat(service.upload(1,null,"V2","修改结构",null).getOwnerObjectType()).isEqualTo("Document");
        verify(storage,never()).uploadProjectAttachment(any(),any(),any(),any(),any(),any());
    }
    @Test void standaloneCadAcceptsExplicitSharedObjectPdfAndFreezesScope() {
        actor();var a=file(1,"step");a.setOwnerObjectType("Document");a.setOwnerObjectId(1L);
        var pdf=file(2,"pdf");pdf.setOwnerObjectType("Document");pdf.setOwnerObjectId(2L);
        when(jdbc.queryForObject(contains("plm_document_reference"),eq(Boolean.class),eq(1L),eq(2L))).thenReturn(true);
        when(jdbc.queryForList(startsWith("select r.object_type"),eq(1L))).thenReturn(new ArrayList<>(List.of(new HashMap<>(Map.of("type","source_code","id",8L,"partType","ALL","code","MAB200001","name","结构模")))));
        var refs=List.of(new DocumentService.Reference("source_code",8,"ALL"));
        service.publish(1,new AttachmentVersionService.Publication("确认",null,null,2L,refs),null);
        assertThat(a.getPublicationReferencesJson()).contains("MAB200001");
        assertThatThrownBy(()->service.publish(1,new AttachmentVersionService.Publication("确认",null,null,2L,List.of(new DocumentService.Reference("source_code",9,"ALL"))),null)).hasMessageContaining("固定");
    }
}
