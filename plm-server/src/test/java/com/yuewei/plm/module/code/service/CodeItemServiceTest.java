package com.yuewei.plm.module.code.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.code.dto.CodeItemSaveDTO;
import com.yuewei.plm.module.code.dto.CodeItemQueryDTO;
import com.yuewei.plm.module.code.entity.CodeItem;
import com.yuewei.plm.module.code.repository.CodeItemRepository;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CodeItemServiceTest {
    private CodeItemRepository repository;
    private CodeItemService service;

    @BeforeEach
    void setUp() {
        repository = mock(CodeItemRepository.class);
        org.mockito.Mockito.doAnswer(call->{((com.yuewei.plm.module.code.entity.CodeItem)call.getArgument(0)).setCodeItemId(55L);return 1;}).when(repository).insert(any(com.yuewei.plm.module.code.entity.CodeItem.class));
        service = new CodeItemService(repository);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"lifecycle",mock(CodeLifecycleService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"jdbc",mock(org.springframework.jdbc.core.JdbcTemplate.class));
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),"production-code-test"),CodeItem.class);
    }

    @Test
    void createPreservesLeadingZero() {
        when(repository.selectCount(any(Wrapper.class))).thenReturn(0L);
        CodeItemSaveDTO dto = new CodeItemSaveDTO();
        dto.setCodeType("color");
        dto.setCodeValue("02");
        dto.setCodeName("Negro");
        dto.setSortOrder(2);

        var created = service.create(dto);

        assertThat(created.getCodeValue()).isEqualTo("02");
        verify(repository).insert(any(CodeItem.class));
    }

    @Test
    void pageAllowsStatusToBeOmitted() {
        when(repository.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(new Page<>());
        CodeItemQueryDTO query = new CodeItemQueryDTO();

        var result = service.page(query);

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    void createRejectsDuplicateWithinType() {
        when(repository.selectCount(any(Wrapper.class))).thenReturn(1L);
        CodeItemSaveDTO dto = new CodeItemSaveDTO();
        dto.setCodeType("color");
        dto.setCodeValue("02");
        dto.setCodeName("Negro");
        dto.setSortOrder(2);

        assertThatThrownBy(() -> service.create(dto))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("编码已存在");
    }

    @Test
    void phoneCodeIsFourDigitStringAndNameIsTrimmed() {
        CodeItemSaveDTO dto = phone("0001", "  Apple iPhone 16  ");
        var result = service.create(dto);
        assertThat(result.getCodeType()).isEqualTo("phone_model");
        assertThat(result.getCodeValue()).isEqualTo("0001");
        assertThat(result.getCodeName()).isEqualTo("Apple iPhone 16");
    }

    @Test
    void phoneRejectsInvalidLengthsAndNonDigits() {
        for (String value : new String[]{"1", "001", "00001", "00a1", "０００１"})
            assertThatThrownBy(() -> service.create(phone(value, "Model")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("四位数字");
        verify(repository, never()).insert(any(CodeItem.class));
    }

    @Test
    void phoneNameIsUniqueEvenWhenCodeIsDifferentOrRecordIsDisabled() {
        when(repository.selectCount(any(Wrapper.class))).thenReturn(1L);
        assertThatThrownBy(() -> service.create(phone("0001", " apple IPHONE 16 ")))
            .isInstanceOf(BusinessException.class).hasMessageContaining("手机型号名称已存在");
    }

    @Test
    void phoneCodeAndTypeAreImmutableButNameCanBeEdited() {
        CodeItem item = new CodeItem();
        item.setCodeItemId(12L); item.setCodeType("phone_model"); item.setCodeValue("0001");
        item.setCodeName("Before"); item.setDeletedFlag(0); item.setStatus("disabled"); item.setVersion(0L);
        when(repository.selectById(12L)).thenReturn(item);
        assertThatThrownBy(() -> service.update(12L, phone("0002", "After"))).hasMessageContaining("固定保留");
        var updated = service.update(12L, phone("0001", " After "));
        assertThat(updated.getCodeValue()).isEqualTo("0001");
        assertThat(updated.getCodeName()).isEqualTo("After");
        assertThat(updated.getStatus()).isEqualTo("disabled");
    }

    @Test
    void databaseUniquenessRaceIsReportedAsBusinessConflict() {
        org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("duplicate"))
            .when(repository).insert(any(CodeItem.class));
        assertThatThrownBy(() -> service.create(phone("0001", "Model")))
            .isInstanceOf(BusinessException.class).hasMessageContaining("手机型号编码或名称已存在");
    }

    @Test
    void phoneCodesCannotBeUsedAsColors() {
        CodeItem item = new CodeItem(); item.setCodeType("phone_model"); item.setCodeValue("0001"); item.setDeletedFlag(0);
        when(repository.selectById(12L)).thenReturn(item);
        assertThatThrownBy(() -> service.requireEnabledColor(12L, "0001")).hasMessageContaining("颜色编码不存在");
    }

    @Test
    void phoneQueryUsesDescendingCodeAndStableIdWhileColorOrderIsUnchanged() {
        when(repository.selectPage(any(Page.class),any(Wrapper.class))).thenReturn(new Page<>());
        var query=new CodeItemQueryDTO();query.setCodeType("phone_model");service.page(query);
        query.setCodeType("color");service.page(query);
        var capture=org.mockito.ArgumentCaptor.forClass(Wrapper.class);verify(repository,org.mockito.Mockito.times(2)).selectPage(any(Page.class),capture.capture());
        assertThat(capture.getAllValues().get(0).getSqlSegment()).contains("ORDER BY code_value DESC,code_item_id DESC").doesNotContain("sort_order");
        assertThat(capture.getAllValues().get(1).getSqlSegment()).contains("ORDER BY sort_order ASC,code_value ASC");
    }
    @Test
    void brandUnclassifiedAndProductionFiltersCannotBeUsedForColorsOrMixedTogether() {
        var q=new CodeItemQueryDTO();q.setBrandId(1L);assertThatThrownBy(()->service.page(q)).hasMessageContaining("仅用于手机编码");
        q.setCodeType("phone_model");q.setUnclassified(true);assertThatThrownBy(()->service.page(q)).hasMessageContaining("不可同时");
        q.setBrandId(null);q.setProductionStatus("archived");assertThatThrownBy(()->service.page(q)).hasMessageContaining("生产状态");
    }
    @Test
    void newlyCreatedPhoneHasUnknownProductionStateRatherThanEnabledBusinessState() {
        assertThat(service.create(phone("0003","New phone")).getProductionStatus()).isEqualTo("unknown");
    }

    private CodeItemSaveDTO phone(String value, String name) {
        CodeItemSaveDTO dto = new CodeItemSaveDTO();
        dto.setVersion(0L);dto.setReason("核对手机资料");dto.setCodeType("phone_model"); dto.setCodeValue(value); dto.setCodeName(name); dto.setSortOrder(0);
        return dto;
    }
}
