package com.yuewei.plm.module.code.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuewei.plm.common.constant.ErrorCodeConstants;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.vo.PageVO;
import com.yuewei.plm.module.code.dto.CodeItemQueryDTO;
import com.yuewei.plm.module.code.dto.CodeItemSaveDTO;
import com.yuewei.plm.module.code.entity.CodeItem;
import com.yuewei.plm.module.code.repository.CodeItemRepository;
import com.yuewei.plm.module.code.vo.CodeItemVO;
import java.time.LocalDateTime;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.dao.DuplicateKeyException;

@Service
@RequiredArgsConstructor
public class CodeItemService {
    private final CodeItemRepository repository;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.yuewei.plm.module.productionmetadata.PhoneCodeMetadataRepository phoneMetadata;
    @org.springframework.beans.factory.annotation.Autowired
    private com.yuewei.plm.module.moldreference.PhoneModelReferenceService phoneReferences;

    @org.springframework.beans.factory.annotation.Autowired
    private CodeLifecycleService lifecycle;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    public PageVO<CodeItemVO> page(CodeItemQueryDTO query) {
        lifecycle.read();
        String normalizedStatus = StringUtils.hasText(query.getStatus()) ? normalizeStatus(query.getStatus()) : null;
        LambdaQueryWrapper<CodeItem> wrapper = new LambdaQueryWrapper<CodeItem>()
            .eq(CodeItem::getDeletedFlag, 0)
            .eq(StringUtils.hasText(query.getCodeType()), CodeItem::getCodeType, normalizeType(query.getCodeType()))
            .eq(normalizedStatus != null, CodeItem::getStatus, normalizedStatus)
            .and(StringUtils.hasText(query.getKeyword()), value -> value
                .like(CodeItem::getCodeValue, query.getKeyword().trim())
                .or().like(CodeItem::getCodeName, query.getKeyword().trim())
                .or().like(CodeItem::getCodeNameZh, query.getKeyword().trim()));
        boolean phone="phone_model".equalsIgnoreCase(query.getCodeType());
        if(!phone&&(query.getBrandId()!=null||Boolean.TRUE.equals(query.getUnclassified())||StringUtils.hasText(query.getProductionStatus())))throw validation("品牌和生产状态筛选仅用于手机编码");
        if(query.getBrandId()!=null&&Boolean.TRUE.equals(query.getUnclassified()))throw validation("品牌与未归类筛选不可同时使用");
        if(phone){
            if(query.getBrandId()!=null)wrapper.apply("exists(select 1 from plm_phone_code_brand_effective eb where eb.code_item_id=plm_code_item.code_item_id and eb.brand_id={0})",query.getBrandId());
            if(Boolean.TRUE.equals(query.getUnclassified()))wrapper.apply("not exists(select 1 from plm_phone_code_brand_effective eb where eb.code_item_id=plm_code_item.code_item_id)");
            if(StringUtils.hasText(query.getProductionStatus()))wrapper.eq(CodeItem::getProductionStatus,com.yuewei.plm.module.productionmetadata.ProductionStatus.normalize(query.getProductionStatus()));
            wrapper.orderByDesc(CodeItem::getCodeValue,CodeItem::getCodeItemId);
        }else wrapper.orderByAsc(CodeItem::getSortOrder,CodeItem::getCodeValue);
        IPage<CodeItem> result = repository.selectPage(new Page<>(query.getPage(), query.getSize()), wrapper);
        var rows=result.getRecords().stream().map(this::toVO).toList();
        if(phoneMetadata!=null)phoneMetadata.enrich(rows);
        return PageVO.<CodeItemVO>builder()
            .content(rows)
            .page(result.getCurrent()).size(result.getSize())
            .totalElements(result.getTotal()).totalPages(result.getPages()).build();
    }

    @Transactional
    public CodeItemVO create(CodeItemSaveDTO dto) {
        lifecycle.maintenance();
        String type = normalizeType(dto.getCodeType());
        String value = required(dto.getCodeValue(), "编码值");
        String name = required(dto.getCodeName(), "编码名称");
        if("phone_model".equals(type)&&phoneReferences!=null)phoneReferences.lockCodeSelectionMaintenance();
        if ("phone_model".equals(type)) {
            validatePhoneCode(value);
            ensurePhoneNameUnique(name, null);
        }
        ensureUnique(type, value, null);
        CodeItem item = new CodeItem();
        item.setCodeType(type);
        item.setCodeValue(value);
        item.setCodeName(name);
        item.setCodeNameZh(optional(dto.getCodeNameZh()));
        item.setStatus("enabled");
        item.setSortOrder(dto.getSortOrder());
        fillCreate(item);
        try { repository.insert(item); }
        catch (DuplicateKeyException exception) { throw duplicate(type); }
        syncPhone(item);
        lifecycle.saved(item.getCodeItemId(),null,toVO(item),"新增基础编码");
        return toVO(item);
    }

    @Transactional
    public CodeItemVO update(Long id, CodeItemSaveDTO dto) {
        lifecycle.maintenance();
        jdbc.queryForList("select code_item_id from plm_code_item where code_item_id=? for update",id);
        CodeItem item = requireItem(id);
        var before=toVO(item);
        if(dto.getVersion()==null||!java.util.Objects.equals(dto.getVersion(),item.getVersion()))throw new BusinessException(40901,"编码已变化，请刷新后重新编辑");
        if(dto.getReason()==null||dto.getReason().isBlank())throw validation("请填写修改原因");
        if(!item.getCodeType().equals(normalizeType(dto.getCodeType()))||!item.getCodeValue().equals(required(dto.getCodeValue(),"编码值")))throw validation("编码值与类型创建后固定保留");
        if("phone_model".equals(item.getCodeType())&&phoneReferences!=null)phoneReferences.lockCodeSelectionMaintenance();
        String name = required(dto.getCodeName(), "编码名称");
        if ("phone_model".equals(item.getCodeType())) {
            if (!item.getCodeType().equals(normalizeType(dto.getCodeType()))
                || !item.getCodeValue().equals(required(dto.getCodeValue(), "编码值")))
                throw validation("手机型号编码创建后不可修改编码值或类型");
            ensurePhoneNameUnique(name, id);
        }
        item.setVersion(item.getVersion()+1);
        item.setCodeName(name);
        item.setCodeNameZh(optional(dto.getCodeNameZh()));
        item.setSortOrder(dto.getSortOrder());
        touch(item);
        try { repository.updateById(item); }
        catch (DuplicateKeyException exception) { throw duplicate(item.getCodeType()); }
        syncPhone(item);
        lifecycle.saved(id,before,toVO(item),dto.getReason().strip());
        return toVO(item);
    }

    public CodeItem requireEnabledColor(Long id, String codeValue) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())jdbc.queryForList("select code_item_id from plm_code_item where code_item_id=? for share",id);
        CodeItem item = requireItem(id);
        if (!"color".equals(item.getCodeType()) || !item.getCodeValue().equals(codeValue)) {
            throw validation("颜色编码不存在或与选择值不一致");
        }
        if (!"enabled".equals(item.getStatus())) {
            throw validation("颜色编码已停用");
        }
        return item;
    }

    private void ensureUnique(String type, String value, Long excludedId) {
        long count = repository.selectCount(new LambdaQueryWrapper<CodeItem>()
            .eq(CodeItem::getCodeType, type).eq(CodeItem::getCodeValue, value)
            .ne(excludedId != null, CodeItem::getCodeItemId, excludedId));
        if (count > 0) throw new BusinessException(ErrorCodeConstants.CODE_CONFLICT, "编码已存在");
    }

    private void validatePhoneCode(String value) {
        if (!value.matches("[0-9]{4}")) throw validation("手机型号编码必须为四位数字，保留前导零");
    }

    private void ensurePhoneNameUnique(String name, Long excludedId) {
        long count = repository.selectCount(new LambdaQueryWrapper<CodeItem>()
            .eq(CodeItem::getCodeType, "phone_model").eq(CodeItem::getDeletedFlag, 0)
            .apply("lower(btrim(code_name)) = lower({0})", name)
            .ne(excludedId != null, CodeItem::getCodeItemId, excludedId));
        if (count > 0) throw new BusinessException(ErrorCodeConstants.CODE_CONFLICT, "手机型号名称已存在（忽略大小写及首尾空格）");
    }

    private BusinessException duplicate(String type) {
        return new BusinessException(ErrorCodeConstants.CODE_CONFLICT,
            "phone_model".equals(type) ? "手机型号编码或名称已存在" : "编码已存在");
    }

    private CodeItem requireItem(Long id) {
        CodeItem item = repository.selectById(id);
        if (item == null || Integer.valueOf(1).equals(item.getDeletedFlag())) {
            throw new BusinessException(ErrorCodeConstants.RESOURCE_NOT_FOUND, "编码不存在");
        }
        return item;
    }

    private String normalizeType(String value) {
        String type=required(value, "编码类型").toLowerCase(Locale.ROOT);if(!java.util.Set.of("color","phone_model").contains(type))throw validation("编码类型无效");return type;
    }

    private String normalizeStatus(String value) {
        String status = required(value, "状态").toLowerCase(Locale.ROOT);
        if (!"enabled".equals(status) && !"disabled".equals(status)) throw validation("编码状态无效");
        return status;
    }

    private String required(String value, String field) {
        if (!StringUtils.hasText(value)) throw validation(field + "不能为空");
        return value.trim();
    }

    private void fillCreate(CodeItem item) {
        LocalDateTime now = LocalDateTime.now();
        item.setVersion(0L); item.setCreatedAt(now); item.setCreatedBy(lifecycle.actorName());
        item.setUpdatedAt(now); item.setUpdatedBy(lifecycle.actorName()); item.setDeletedFlag(0);
    }

    private void touch(CodeItem item) {
        item.setUpdatedAt(LocalDateTime.now()); item.setUpdatedBy(lifecycle.actorName());
    }

    private CodeItemVO toVO(CodeItem item) {
        return CodeItemVO.builder().codeItemId(item.getCodeItemId()).codeType(item.getCodeType())
            .codeValue(item.getCodeValue()).codeName(item.getCodeName()).codeNameZh(item.getCodeNameZh())
            .status(item.getStatus())
            .productionStatus("phone_model".equals(item.getCodeType())?com.yuewei.plm.module.productionmetadata.ProductionStatus.display(item.getProductionStatus()):null)
            .sortOrder(item.getSortOrder()).version(item.getVersion()).updatedAt(item.getUpdatedAt()).build();
    }
    private void syncPhone(CodeItem item) {
        if(phoneReferences==null||!"phone_model".equals(item.getCodeType()))return;
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        var request=attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet?servlet.getRequest():null;
        phoneReferences.syncCodeSelectionAfterMaintenance(item.getCodeItemId(),request);
    }

    private String optional(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, message);
    }
}
