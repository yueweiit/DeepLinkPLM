package com.yuewei.plm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.constant.ErrorCodeConstants;
import com.yuewei.plm.common.constant.ProductStatusConstants;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.security.CurrentUserContext;
import com.yuewei.plm.common.util.ProductBusinessCodeGenerator;
import com.yuewei.plm.common.util.ProductCodeGenerator;
import com.yuewei.plm.common.vo.PageVO;
import com.yuewei.plm.controller.dto.ProductCreateDTO;
import com.yuewei.plm.controller.dto.ProductLifecycleActionDTO;
import com.yuewei.plm.controller.dto.ProductQueryDTO;
import com.yuewei.plm.controller.dto.ProductUpdateDTO;
import com.yuewei.plm.module.operationlog.constant.OperationActionConstants;
import com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.order.service.ProjectOrderLifecycleSync;
import com.yuewei.plm.module.bom.entity.ProductProductionColorDecision;
import com.yuewei.plm.module.bom.repository.ProductProductionColorDecisionRepository;
import com.yuewei.plm.module.bom.service.BomInheritanceService;
import com.yuewei.plm.module.process.service.ProcessRouteInheritanceService;
import com.yuewei.plm.module.project.variant.entity.ProductVariantColor;
import com.yuewei.plm.module.project.variant.entity.RequirementForm;
import com.yuewei.plm.module.project.variant.repository.ProductVariantColorRepository;
import com.yuewei.plm.module.project.variant.repository.RequirementFormRepository;
import com.yuewei.plm.module.product.mold.service.ProductMoldCodeService;
import com.yuewei.plm.module.product.mold.vo.ProductMoldCodeVO;
import com.yuewei.plm.module.workflow.entity.WorkflowTemplate;
import com.yuewei.plm.module.workflow.service.WorkflowTemplateService;
import com.yuewei.plm.repository.ProductRepository;
import com.yuewei.plm.repository.entity.Product;
import com.yuewei.plm.service.ProductReleaseGateValidator;
import com.yuewei.plm.service.ProductService;
import com.yuewei.plm.service.vo.ProductCreateResultVO;
import com.yuewei.plm.service.vo.ProductProductionColorVO;
import com.yuewei.plm.service.vo.ProductReleaseGateCheckVO;
import com.yuewei.plm.service.vo.ProductReleaseGateMissingItemVO;
import com.yuewei.plm.service.vo.ProductVO;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {
    @Autowired
    private com.yuewei.plm.module.productionmetadata.ProductSalesPolicy productSalesPolicy;
    @Autowired(required=false) private org.springframework.jdbc.core.JdbcTemplate masterDataJdbc;

    private final ProductRepository productRepository;
    private final ProductCodeGenerator productCodeGenerator;
    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;
    private final ProductReleaseGateValidator productReleaseGateValidator;
    private final BomInheritanceService bomInheritanceService;
    private final ProcessRouteInheritanceService processRouteInheritanceService;
    private final ProductProductionColorDecisionRepository colorDecisionRepository;
    private final ProductVariantColorRepository variantColorRepository;
    private final ProductBusinessCodeGenerator businessCodeGenerator = new ProductBusinessCodeGenerator();
    @Autowired(required = false)
    private ProjectOrderLifecycleSync projectOrderLifecycleSync;
    @Autowired(required = false)
    private WorkflowTemplateService workflowTemplateService;
    @Autowired(required = false)
    private ProductMoldCodeService productMoldCodeService;
    @Autowired(required = false)
    private RequirementFormRepository requirementFormRepository;
    @Autowired(required = false)
    private com.yuewei.plm.module.moldreference.PhoneModelReferenceService phoneModelReferences;
    @Autowired(required = false)
    private com.yuewei.plm.module.pilot.PilotAccessService pilotAccessService;
    @Autowired(required = false)
    private com.yuewei.plm.module.project.development.ProductDevelopmentService productDevelopmentService;

    @Override
    public PageVO<ProductVO> page(ProductQueryDTO queryDTO) {
        LambdaQueryWrapper<Product> queryWrapper = new LambdaQueryWrapper<Product>()
            .eq(queryDTO.getCustomerId() != null, Product::getCustomerId, queryDTO.getCustomerId())
            .eq(queryDTO.getParentProductId() != null, Product::getParentProductId, queryDTO.getParentProductId())
            .eq(StringUtils.hasText(queryDTO.getStatus()), Product::getStatus, queryDTO.getStatus())
            .eq(StringUtils.hasText(queryDTO.getProductionStatus()),Product::getProductionStatus,StringUtils.hasText(queryDTO.getProductionStatus())?com.yuewei.plm.module.productionmetadata.ProductionStatus.normalize(queryDTO.getProductionStatus()):null)
            .eq(StringUtils.hasText(queryDTO.getProductType()), Product::getProductType, queryDTO.getProductType())
            .and(StringUtils.hasText(queryDTO.getKeyword()), wrapper -> wrapper
                .like(Product::getProductName, queryDTO.getKeyword())
                .or()
                .like(Product::getProductCode, queryDTO.getKeyword())
                .or()
                .like(Product::getModel, queryDTO.getKeyword())
                .or()
                .like(Product::getProductSpecificCode, normalizeCode(queryDTO.getKeyword()))
                .or()
                .like(Product::getPhoneModelCode, normalizeCode(queryDTO.getKeyword()))
                .or()
                .like(Product::getColorCode, normalizeCode(queryDTO.getKeyword()))
                .or()
                .like(Product::getFinishedProductCode, normalizeCode(queryDTO.getKeyword()))
                .or()
                .like(Product::getImportShortCode, normalizeCode(queryDTO.getKeyword())))
            .orderByDesc(Product::getCreatedAt);

        IPage<Product> page = productRepository.selectPage(new Page<>(queryDTO.getPage(), queryDTO.getSize()), queryWrapper);
        return PageVO.<ProductVO>builder()
            .content(page.getRecords().stream().map(this::toVO).toList())
            .page(page.getCurrent())
            .size(page.getSize())
            .totalElements(page.getTotal())
            .totalPages(page.getPages())
            .build();
    }

    @Override
    public ProductVO getById(Long productId) {
        return toVO(getProductOrThrow(productId));
    }

    @Override
    public List<ProductProductionColorVO> listProductionColors(Long productId) {
        getProductOrThrow(productId);
        return confirmedProductionColorDecisions(productId).stream()
            .map(this::toProductionColorVO)
            .toList();
    }

    @Override
    @Transactional
    public ProductCreateResultVO create(ProductCreateDTO createDTO) {
        if ("lightweight".equals(createDTO.getDevelopmentMode())) throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR,"旧版新品研发流程已停用，请使用新版新品研发");
        boolean research = "product_line".equals(createDTO.getProductType())
            && "launch_v1".equals(createDTO.getDevelopmentMode());
        if (pilotAccessService != null) {
            pilotAccessService.requireDomesticView();
            pilotAccessService.requirePermission("documents:write");
        }
        Map<String,Object> selectedPhone=validateSelectedPhone(createDTO);
        boolean modelVariant = "model_variant".equals(createDTO.getProductType());
        Product parent = modelVariant ? requireReleasedVariantParent(createDTO.getParentProductId()) : null;
        if (modelVariant) {
            productSalesPolicy.requireOnSale(List.of(parent.getProductId()));
            if(masterDataJdbc!=null) com.yuewei.plm.module.masterdata.ProductMoldLifecycleService.requireActive(masterDataJdbc,"product",parent.getProductId());
        }
        List<ProductProductionColorDecision> inheritedColors = modelVariant
            ? requireConfirmedProductionColors(parent.getProductId())
            : List.of();
        LocalDateTime now = LocalDateTime.now();

        Product product = new Product();
        product.setParentProductId(modelVariant ? parent.getProductId() : null);
        product.setCustomerId(createDTO.getCustomerId());
        product.setProductCode(productCodeGenerator.generate(createDTO.getProductName()));
        product.setProductName(createDTO.getProductName());
        product.setProductType(createDTO.getProductType());
        if (research) {
            product.setDevelopmentMode(createDTO.getDevelopmentMode());
            product.setDevelopmentRequirements(createDTO.getDevelopmentRequirements() == null ? createDTO.getRemark() : createDTO.getDevelopmentRequirements());
            product.setTargetCost(createDTO.getTargetCost());
            product.setExpectedDeliveryDate(createDTO.getExpectedDeliveryDate());
            product.setCurrentStepNo(1);
        }
        product.setSeriesName(modelVariant ? parent.getSeriesName() : createDTO.getSeriesName());
        product.setModel(createDTO.getModel());
        product.setPhoneModelMemberId(createDTO.getPhoneModelId());
        product.setColor(modelVariant ? null : createDTO.getColor());
        applyBusinessCodesForCreate(product, createDTO, parent, inheritedColors);
        if ("product_line".equals(product.getProductType())) {
            String productLineCode = resolveProductLineProductCode(createDTO.getFinishedProductCode(), product.getProductSpecificCode());
            if (StringUtils.hasText(productLineCode)) {
                product.setProductCode(productLineCode);
                product.setFinishedProductCode(null);
            }
        }
        product.setMaterial(createDTO.getMaterial());
        product.setPackageType(createDTO.getPackageType());
        product.setSurfaceProcess(createDTO.getSurfaceProcess());
        product.setCoreProcess(createDTO.getCoreProcess());
        product.setComposition(createDTO.getComposition());
        product.setOwnerUserId(createDTO.getOwnerUserId());
        product.setVersionNo(createDTO.getVersionNo());
        product.setStatus(ProductStatusConstants.DRAFT);
        product.setLockStatus("unlocked");
        product.setRemark(createDTO.getRemark());
        product.setCreatedAt(now);
        product.setCreatedBy(createDTO.getCreatedBy());
        product.setUpdatedAt(now);
        product.setUpdatedBy(createDTO.getCreatedBy());
        product.setDeletedFlag(0);
        if (pilotAccessService != null) {
            CurrentUser actor = pilotAccessService.actor();
            if (actor != null) {
                product.setCreatedBy(research ? actor.displayName() : actor.username());
                product.setUpdatedBy(research ? actor.displayName() : actor.username());
            }
        }
        ensureProductBusinessCodeAvailable(product, null);
        bindWorkflowTemplate(product);

        com.yuewei.plm.module.project.ProjectRegistration.initialize(product,now);
        productRepository.insert(product);
        if (research && productDevelopmentService != null) productDevelopmentService.registerCreated(product);
        if (modelVariant) {
            createVariantColorSnapshots(product, parent, inheritedColors, createDTO.getCreatedBy(), now);
            List<String> inheritedColorNames = inheritedColors.stream()
                .map(ProductProductionColorDecision::getColorName)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
            Map<Long, Long> processIdMapping = processRouteInheritanceService.inheritLatestReleasedFormalBomRoutesByColors(
                parent.getProductId(), product, inheritedColorNames, createDTO.getCreatedBy());
            bomInheritanceService.inheritLatestReleasedByColors(parent.getProductId(), product.getProductId(),
                inheritedColorNames, processIdMapping);
        }
        if(selectedPhone!=null) {
            try {operationLogService.logRequired(OperationLogCreateCommand.builder().action("PRODUCT_CREATE").businessType("PRODUCT").businessId(String.valueOf(product.getProductId())).businessCode(product.getProductCode()).businessName(product.getProductName()).detailJson(objectMapper.writeValueAsString(Map.of("phoneModelId",createDTO.getPhoneModelId(),"phoneCodeItemId",selectedPhone.get("codeItemId"),"phoneModelCode",product.getPhoneModelCode(),"model",product.getModel(),"reason","从标准手机成员明确选择立项，原刻码不改写"))).build(),pilotAccessService.actor());}
            catch(JsonProcessingException failure) {throw new IllegalStateException("项目手机关联审计失败",failure);}
        }
        return ProductCreateResultVO.builder()
            .productId(product.getProductId())
            .productCode(product.getProductCode())
            .versionNo(product.getVersionNo())
            .status(product.getStatus())
            .build();
    }

    @Override
    @Transactional
    public ProductVO update(Long productId, ProductUpdateDTO updateDTO) {
        Product product = getProductOrThrow(productId);
        if (isReleasedResearch(product)) return assignReleasedResearchIdentity(productId, updateDTO);
        if(pilotAccessService!=null){pilotAccessService.requireDomesticView();pilotAccessService.requirePermission("documents:write");pilotAccessService.requireProduct(productId);}
        if (productDevelopmentService != null && com.yuewei.plm.module.project.development.ProductDevelopmentService.isLaunch(product)) {
            product = productDevelopmentService.requireEditable(productId);
        }
        ensureEditable(product);
        String previousSpecificCode=product.getProductSpecificCode();
        applyBasicInfoUpdates(product, updateDTO);
        applyBusinessCodesForUpdate(product, updateDTO);
        product.setUpdatedAt(LocalDateTime.now());
        product.setUpdatedBy(updateDTO.getUpdatedBy());
        ensureProductBusinessCodeAvailable(product, productId);
        productRepository.updateById(product);
        syncRequirementFormFromBasicInfo(product, updateDTO);
        auditInitialResearchCode(product,previousSpecificCode);
        return toVO(product);
    }

    @Override
    @Transactional
    public ProductVO updateBasicInfo(Long productId, ProductUpdateDTO updateDTO) {
        Product product = getProductOrThrow(productId);
        if (isReleasedResearch(product)) return assignReleasedResearchIdentity(productId, updateDTO);
        if(pilotAccessService!=null){pilotAccessService.requireDomesticView();pilotAccessService.requirePermission("documents:write");pilotAccessService.requireProduct(productId);}
        if (productDevelopmentService != null && com.yuewei.plm.module.project.development.ProductDevelopmentService.isLaunch(product)) {
            product = productDevelopmentService.requireEditable(productId);
        }
        if (com.yuewei.plm.module.project.development.ProductDevelopmentService.isLaunch(product)) ensureEditable(product);
        String previousSpecificCode=product.getProductSpecificCode();
        applyBasicInfoUpdates(product, updateDTO);
        product.setUpdatedAt(LocalDateTime.now());
        product.setUpdatedBy(updateDTO.getUpdatedBy());
        if(!java.util.Objects.equals(previousSpecificCode,product.getProductSpecificCode()))ensureProductBusinessCodeAvailable(product,productId);
        productRepository.updateById(product);
        syncRequirementFormFromBasicInfo(product, updateDTO);
        auditInitialResearchCode(product,previousSpecificCode);
        return toVO(product);
    }

    private boolean isReleasedResearch(Product product) {
        return com.yuewei.plm.module.project.development.ProductDevelopmentService.isLaunch(product)
            && ProductStatusConstants.RELEASED.equals(product.getStatus());
    }

    @Override
    @Transactional
    public String ensureResearchProductSpecificCode(Long productId, String operator) {
        Product product = productRepository.selectIdentityForUpdate(productId);
        if (product == null || !com.yuewei.plm.module.project.development.ProductDevelopmentService.isLaunch(product))
            throw new BusinessException(40001, "自动登记编码仅用于原新品项目");
        if (java.util.Set.of("archived", "abandoned").contains(java.util.Objects.toString(product.getStatus(), ""))
            || java.util.Set.of("frozen", "abandoned").contains(java.util.Objects.toString(product.getLockStatus(), "")))
            throw new BusinessException(40001, "已停用或冻结的新品不能自动登记编码");
        if (StringUtils.hasText(product.getProductSpecificCode())) return product.getProductSpecificCode();
        if (masterDataJdbc == null) throw new BusinessException(50301, "产品编码登记服务暂不可用");
        // The product table and imported code sources reserve identities; no second allocation ledger is needed.
        masterDataJdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, "product-specific:auto");
        var occupied = new java.util.HashSet<>(masterDataJdbc.queryForList("""
            select upper(btrim(value)) from (
              select product_specific_code value from plm_product
              union all select regexp_replace(upper(btrim(product_code_prefix)),'^[MN](?=[A-Z]{2}$)','') from plm_product
              union all select substring(upper(mold_code_prefix) from '^M([A-Z]{2})') from plm_product
              union all select substring(upper(product_code) from '^[MN]([A-Z]{2})[0-9]') from plm_product
              union all select substring(upper(finished_product_code) from '^N([A-Z]{2})[0-9]') from plm_product
              union all select product_specific_code from plm_product_mold_code
              union all select substring(upper(mold_code) from '^M([A-Z]{2})') from plm_product_mold_code
              union all select product_specific_code from plm_mold_code_source
              union all select substring(upper(mold_code) from '^[MN]([A-Z]{2})') from plm_mold_code_source
              union all select substring(upper(inventory_code) from '^[MN]([A-Z]{2})[0-9]') from plm_inventory
              union all select (regexp_matches(upper(generated_codes),'M([A-Z]{2})[0-9]{6}','g'))[1] from plm_mold_application
            ) codes where value is not null
            """, String.class));
        for (char first = 'A'; first <= 'Z'; first++) for (char second = 'A'; second <= 'Z'; second++) {
            String candidate = "" + first + second;
            if (occupied.contains(candidate)) continue;
            masterDataJdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, "product-specific:" + candidate);
            if (masterDataJdbc.queryForObject("select count(*) from plm_product where product_specific_code=?", Long.class, candidate) > 0) continue;
            product.setProductSpecificCode(candidate);
            product.setProductCodePrefix(candidate);
            product.setMoldCodePrefix("M" + candidate);
            product.setUpdatedAt(LocalDateTime.now());
            product.setUpdatedBy(operator);
            ensureProductBusinessCodeAvailable(product, productId);
            productRepository.updateById(product);
            auditInitialResearchCode(product, null);
            return candidate;
        }
        throw new BusinessException(ErrorCodeConstants.CODE_CONFLICT, "两位产品编码已用尽，请核对现有编码范围");
    }

    /** Identity-only completion cannot reopen a released technical version or resave its requirements. */
    private ProductVO assignReleasedResearchIdentity(Long id, ProductUpdateDTO dto) {
        if(pilotAccessService==null)throw new BusinessException(40001,"工程授权暂不可用，请稍后重试");
        pilotAccessService.requireDomesticView();pilotAccessService.requirePermission("project:view");
        pilotAccessService.requirePermission("product:write");pilotAccessService.requirePermission("documents:write");
        var actor=pilotAccessService.requireEngineeringMaintenance();pilotAccessService.requireProduct(id);
        var payload=objectMapper.valueToTree(dto);var fields=payload.fields();
        while(fields.hasNext()){
            var field=fields.next();if(!field.getValue().isNull()&&!java.util.Set.of("productSpecificCode","updatedBy").contains(field.getKey()))
                throw new BusinessException(40001,"已发布新品仅允许首次补填产品特定编码；其他资料须通过正式修订办理");
        }
        Product product=productRepository.selectIdentityForUpdate(id);
        if(product==null||!isReleasedResearch(product))throw new BusinessException(40901,"产品状态已变化，请刷新后核对");
        if(java.util.Set.of("frozen","abandoned").contains(java.util.Objects.toString(product.getLockStatus(),"")))
            throw new BusinessException(ErrorCodeConstants.VERSION_FROZEN,"冻结或停止的新品不可补填编码，请先处理项目状态");
        if(StringUtils.hasText(product.getProductSpecificCode()))throw new BusinessException(40001,"已有新品产品特定编码不可在基础编辑中改写，请保留原编码");
        if(!StringUtils.hasText(dto.getProductSpecificCode()))throw new BusinessException(40001,"请填写两位大写英文字母作为产品特定编码，不能自动猜码");
        String previous=product.getProductSpecificCode();applyBusinessCodesForUpdate(product,dto);
        ensureProductBusinessCodeAvailable(product,id);product.setUpdatedAt(LocalDateTime.now());product.setUpdatedBy(actor.username());
        productRepository.updateById(product);auditInitialResearchCode(product,previous);return toVO(product);
    }

    private void applyBasicInfoUpdates(Product product, ProductUpdateDTO updateDTO) {
        validateStablePhoneIdentity(product, updateDTO);
        if (StringUtils.hasText(updateDTO.getProductName())) {
            product.setProductName(updateDTO.getProductName());
        }
        if (StringUtils.hasText(updateDTO.getSeriesName())) {
            product.setSeriesName(updateDTO.getSeriesName());
        }
        if (StringUtils.hasText(updateDTO.getModel())) {
            product.setModel(updateDTO.getModel());
        }
        if (StringUtils.hasText(updateDTO.getColor())) {
            product.setColor(updateDTO.getColor());
        }
        applyBusinessCodesForUpdate(product, updateDTO);
        if (StringUtils.hasText(updateDTO.getMaterial())) {
            product.setMaterial(updateDTO.getMaterial());
        }
        if (StringUtils.hasText(updateDTO.getPackageType())) {
            product.setPackageType(updateDTO.getPackageType());
        }
        if (StringUtils.hasText(updateDTO.getSurfaceProcess())) {
            product.setSurfaceProcess(updateDTO.getSurfaceProcess());
        }
        if (StringUtils.hasText(updateDTO.getCoreProcess())) {
            product.setCoreProcess(updateDTO.getCoreProcess());
        }
        if (updateDTO.getComposition() != null) {
            product.setComposition(updateDTO.getComposition());
        }
        if (updateDTO.getExpectedDeliveryDate() != null) {
            product.setExpectedDeliveryDate(updateDTO.getExpectedDeliveryDate());
        }
        if (updateDTO.getReferenceUrl() != null) {
            product.setSourceFormUrl(updateDTO.getReferenceUrl());
        }
        if (updateDTO.getExpectedArrivalAt() != null) {
            product.setExpectedArrivalAt(updateDTO.getExpectedArrivalAt());
        }
        if (updateDTO.getActualArrivalAt() != null) {
            product.setActualArrivalAt(updateDTO.getActualArrivalAt());
        }
        if (updateDTO.getRemark() != null) {
            product.setRemark(updateDTO.getRemark());
        }
    }

    private void syncRequirementFormFromBasicInfo(Product product, ProductUpdateDTO updateDTO) {
        if (requirementFormRepository == null || !"model_variant".equals(product.getProductType())) {
            return;
        }
        RequirementForm form = requirementFormRepository.selectList(new LambdaQueryWrapper<RequirementForm>()
                .eq(RequirementForm::getProjectId, product.getProductId())
                .eq(RequirementForm::getDeletedFlag, 0))
            .stream()
            .findFirst()
            .orElse(null);
        if (form == null) {
            return;
        }
        if (updateDTO.getNetworkType() != null) form.setNetworkType(updateDTO.getNetworkType());
        if (updateDTO.getHoleType() != null) form.setHoleType(updateDTO.getHoleType());
        if (updateDTO.getMobileFunction() != null) form.setMobileFunction(updateDTO.getMobileFunction());
        if (updateDTO.getTipo() != null) form.setTipo(updateDTO.getTipo());
        if (updateDTO.getPriority() != null) form.setPriority(updateDTO.getPriority());
        if (updateDTO.getManufacturingLocation() != null) form.setManufacturingLocation(updateDTO.getManufacturingLocation());
        if (updateDTO.getMoldMarking() != null) form.setMoldMarking(updateDTO.getMoldMarking());
        if (updateDTO.getReferenceUrl() != null) form.setReferenceUrl(updateDTO.getReferenceUrl());
        if (updateDTO.getExpectedDeliveryDate() != null) form.setExpectedDeliveryDate(updateDTO.getExpectedDeliveryDate());
        if (updateDTO.getRequirementType() != null) form.setRequirementType(updateDTO.getRequirementType());
        if (updateDTO.getCustomerRequirement() != null) form.setCustomerRequirement(updateDTO.getCustomerRequirement());
        if (updateDTO.getRemark() != null) form.setRemark(updateDTO.getRemark());
        form.setUpdatedAt(product.getUpdatedAt());
        form.setUpdatedBy(updateDTO.getUpdatedBy());
        requirementFormRepository.updateById(form);
    }

    @Override
    @Transactional
    public void freeze(Long productId, String reason, HttpServletRequest request) {
        ProductLifecycleActionDTO dto = new ProductLifecycleActionDTO();
        dto.setReason(reason);
        freeze(productId, dto, request);
    }

    @Override
    @Transactional
    public ProductVO freeze(Long productId, ProductLifecycleActionDTO dto, HttpServletRequest request) {
        // 冻结属于关键业务动作，操作人必须来自 token 上下文，不能由前端参数伪造。
        CurrentUser currentUser = requireCurrentUser();
        String operator = currentUser.displayName();
        Product product = getProductOrThrow(productId);
        if (ProductStatusConstants.RELEASED.equals(product.getStatus())) {
            throw new BusinessException(ErrorCodeConstants.VERSION_RELEASED, "已发布版本不可重复冻结");
        }
        if (ProductStatusConstants.ARCHIVED.equals(product.getStatus())) {
            throw new BusinessException(ErrorCodeConstants.STATUS_TRANSITION_ILLEGAL, "已归档项目不能冻结");
        }
        String reason = dto == null ? null : dto.getReason();
        product.setFrozenAt(LocalDateTime.now());
        product.setFrozenBy(operator);
        product.setFreezeReason(reason);
        product.setLockStatus("frozen");
        product.setLockReason(reason);
        product.setLockOperatorUserId(currentUser.userId());
        product.setLockOperatorUserName(operator);
        product.setLockOperatedAt(LocalDateTime.now());
        product.setUpdatedAt(LocalDateTime.now());
        product.setUpdatedBy(operator);
        productRepository.updateById(product);
        writeProductLog(product, OperationActionConstants.PRODUCT_FREEZE, reason, request);
        return toVO(product);
    }

    @Override
    @Transactional
    public void publish(Long productId, String operator) {
        Product product = getProductOrThrow(productId);
        if (ProductStatusConstants.RELEASED.equals(product.getStatus())) {
            return;
        }
        if (com.yuewei.plm.module.project.development.ProductDevelopmentService.isLaunch(product)) {
            throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, "请从新品研发页面核对版本资料后发布");
        }
        ProductReleaseGateCheckVO gate = productReleaseGateValidator.check(product);
        ensurePublishGate(gate, true);
        publishProductIfNeeded(product, operator);
        writeProductPublishLog(product, "兼容旧产品发布接口", true, gate, null);
    }

    @Override
    public ProductReleaseGateCheckVO checkReleaseGate(Long productId) {
        Product product = getProductOrThrow(productId);
        return productReleaseGateValidator.check(product);
    }

    @Override
    @Transactional
    public ProductVO publish(Long productId, ProductLifecycleActionDTO dto, HttpServletRequest request) {
        Product product = getProductOrThrow(productId);
        CurrentUser currentUser = requireCurrentUser();
        if (productDevelopmentService != null && com.yuewei.plm.module.project.development.ProductDevelopmentService.isLaunch(product)) {
            product = productDevelopmentService.lockForPublication(productId);
        }
        if (ProductStatusConstants.RELEASED.equals(product.getStatus()) && !(productDevelopmentService != null && com.yuewei.plm.module.project.development.ProductDevelopmentService.isLaunch(product) && productDevelopmentService.hasActiveRevision(productId))) {
            return toVO(product);
        }
        ProductReleaseGateCheckVO gate = productReleaseGateValidator.check(product);
        boolean riskConfirmed = dto != null && Boolean.TRUE.equals(dto.getRiskConfirmed());
        ensurePublishGate(gate, riskConfirmed);
        if (productDevelopmentService != null) productDevelopmentService.captureRelease(product, dto, gate);
        publishProductIfNeeded(product, currentUser.displayName());
        writeProductPublishLog(
            product,
            dto == null ? null : dto.getReason(),
            riskConfirmed,
            gate,
            request
        );
        return toVO(product);
    }

    private void publishProductIfNeeded(Product product, String operator) {
        if (ProductStatusConstants.RELEASED.equals(product.getStatus())) {
            return;
        }
        validateStatusTransition(product.getStatus(), ProductStatusConstants.RELEASED);
        LocalDateTime now = LocalDateTime.now();
        product.setStatus(ProductStatusConstants.RELEASED);
        product.setReleasedAt(now);
        product.setReleasedBy(operator);
        product.setUpdatedAt(now);
        product.setUpdatedBy(operator);
        productRepository.updateById(product);
        if (projectOrderLifecycleSync != null) {
            projectOrderLifecycleSync.completed(product.getProductId(), operator);
        }
    }

    private void ensurePublishGate(ProductReleaseGateCheckVO gate, boolean riskConfirmed) {
        boolean blocking = Boolean.TRUE.equals(gate.getBlocking())
            || (!Boolean.TRUE.equals(gate.getPassed()) && !Boolean.TRUE.equals(gate.getConfirmRequired()));
        if (blocking) {
            throw new BusinessException(ErrorCodeConstants.RELEASE_GATE_NOT_PASSED, "发布基础校验未通过", gate);
        }
        if (Boolean.TRUE.equals(gate.getConfirmRequired()) && !riskConfirmed) {
            throw new BusinessException(ErrorCodeConstants.RELEASE_RISK_CONFIRM_REQUIRED, "发布存在资料风险，请确认后继续", gate);
        }
    }

    @Override
    @Transactional
    public ProductVO archive(Long productId, ProductLifecycleActionDTO dto, HttpServletRequest request) {
        Product product = getProductOrThrow(productId);
        CurrentUser currentUser = requireCurrentUser();
        validateStatusTransition(product.getStatus(), ProductStatusConstants.ARCHIVED);
        String reason = dto == null ? null : dto.getReason();
        product.setStatus(ProductStatusConstants.ARCHIVED);
        product.setArchivedAt(LocalDateTime.now());
        product.setArchivedBy(currentUser.displayName());
        product.setArchiveReason(reason);
        product.setUpdatedAt(LocalDateTime.now());
        product.setUpdatedBy(currentUser.displayName());
        productRepository.updateById(product);
        writeProductLog(product, OperationActionConstants.PRODUCT_ARCHIVE, reason, request);
        return toVO(product);
    }

    @Override
    @Transactional
    public ProductVO abandon(Long productId, ProductLifecycleActionDTO dto, HttpServletRequest request) {
        Product product = getProductOrThrow(productId);
        CurrentUser currentUser = requireCurrentUser();
        if (ProductStatusConstants.RELEASED.equals(product.getStatus())) {
            throw new BusinessException(ErrorCodeConstants.STATUS_TRANSITION_ILLEGAL, "已发布产品不能废弃，只能归档或发起变更");
        }
        if (ProductStatusConstants.ARCHIVED.equals(product.getStatus())) {
            throw new BusinessException(ErrorCodeConstants.STATUS_TRANSITION_ILLEGAL, "已归档产品不能重复废弃");
        }
        String reason = dto == null ? null : dto.getReason();
        product.setStatus(ProductStatusConstants.ARCHIVED);
        product.setLockStatus("abandoned");
        product.setAbandonedAt(LocalDateTime.now());
        product.setAbandonedBy(currentUser.displayName());
        product.setAbandonReason(reason);
        product.setUpdatedAt(LocalDateTime.now());
        product.setUpdatedBy(currentUser.displayName());
        productRepository.updateById(product);
        if (projectOrderLifecycleSync != null) projectOrderLifecycleSync.abandoned(productId, reason, currentUser.displayName());
        writeProductLog(product, OperationActionConstants.PRODUCT_ABANDON, reason, request);
        return toVO(product);
    }

    private String toDetailJson(String reason) {
        try {
            return objectMapper.writeValueAsString(Map.of("reason", reason == null ? "" : reason));
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCodeConstants.INTERNAL_ERROR, "操作日志详情序列化失败");
        }
    }

    private void writeProductPublishLog(
        Product product,
        String reason,
        boolean riskConfirmed,
        ProductReleaseGateCheckVO gate,
        HttpServletRequest request
    ) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason == null ? "" : reason);
        detail.put("riskConfirmed", riskConfirmed);
        detail.put("missingItems", gate == null || gate.getMissingItems() == null
            ? List.of()
            : gate.getMissingItems().stream().map(this::toMissingItemLog).toList());
        try {
            operationLogService.logSuccess(OperationLogCreateCommand.builder()
                .action(OperationActionConstants.PRODUCT_PUBLISH)
                .businessType("PRODUCT")
                .businessId(String.valueOf(product.getProductId()))
                .businessCode(product.getProductCode())
                .businessName(product.getProductName())
                .detailJson(objectMapper.writeValueAsString(detail))
                .request(request)
                .build());
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCodeConstants.INTERNAL_ERROR, "操作日志详情序列化失败");
        }
    }

    private Map<String, String> toMissingItemLog(ProductReleaseGateMissingItemVO item) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("code", item.getCode());
        result.put("message", item.getMessage());
        result.put("severity", item.getSeverity());
        return result;
    }

    private Product getProductOrThrow(Long productId) {
        Product product = productRepository.selectById(productId);
        if (product == null || Integer.valueOf(1).equals(product.getDeletedFlag())) {
            throw new BusinessException(ErrorCodeConstants.RESOURCE_NOT_FOUND, "产品不存在");
        }
        return product;
    }

    private CurrentUser requireCurrentUser() {
        return CurrentUserContext.get()
            .orElseThrow(() -> new BusinessException(ErrorCodeConstants.UNAUTHORIZED, "未登录或登录已失效"));
    }

    private void writeProductLog(Product product, String action, String reason, HttpServletRequest request) {
        operationLogService.logSuccess(OperationLogCreateCommand.builder()
            .action(action)
            .businessType("PRODUCT")
            .businessId(String.valueOf(product.getProductId()))
            .businessCode(product.getProductCode())
            .businessName(product.getProductName())
            .detailJson(toDetailJson(reason))
            .request(request)
            .build());
    }

    private Product requireReleasedVariantParent(Long parentProductId) {
        if (parentProductId == null) {
            throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, "型号线必须指定所属产品线");
        }
        Product parent = productRepository.selectById(parentProductId);
        if (parent == null || Integer.valueOf(1).equals(parent.getDeletedFlag())) {
            throw new BusinessException(ErrorCodeConstants.RESOURCE_NOT_FOUND, "所属产品不存在");
        }
        if (!"product_line".equals(parent.getProductType())) {
            throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, "新型号线只能选择新产品线作为对应产品");
        }
        if (!ProductStatusConstants.RELEASED.equals(parent.getStatus())) {
            throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, "对应产品尚未发布，不能创建新型号线");
        }
        return parent;
    }

    private List<ProductProductionColorDecision> requireConfirmedProductionColors(Long parentProductId) {
        List<ProductProductionColorDecision> colors = confirmedProductionColorDecisions(parentProductId);
        if (colors.isEmpty()) {
            throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, "对应产品尚未敲定正式投产颜色，不能创建新型号线");
        }
        return colors;
    }

    private List<ProductProductionColorDecision> confirmedProductionColorDecisions(Long productId) {
        List<ProductProductionColorDecision> colors = colorDecisionRepository.selectList(
            new LambdaQueryWrapper<ProductProductionColorDecision>()
                .eq(ProductProductionColorDecision::getProductId, productId)
                .eq(ProductProductionColorDecision::getSelectedFlag, 1)
                .eq(ProductProductionColorDecision::getStatus, "confirmed")
                .eq(ProductProductionColorDecision::getDeletedFlag, 0)
                .orderByAsc(ProductProductionColorDecision::getColorCode)
                .orderByAsc(ProductProductionColorDecision::getColorName)
        );
        if (colors == null || colors.isEmpty()) {
            return List.of();
        }
        Map<String, ProductProductionColorDecision> distinct = new LinkedHashMap<>();
        for (ProductProductionColorDecision color : colors) {
            String key = color.getCodeItemId() != null
                ? "id:" + color.getCodeItemId()
                : "name:" + (color.getColorCode() == null ? "" : color.getColorCode()) + ":" + color.getColorName();
            distinct.putIfAbsent(key, color);
        }
        return List.copyOf(distinct.values());
    }

    private void createVariantColorSnapshots(Product project, Product parent, List<ProductProductionColorDecision> colors,
                                             String operator, LocalDateTime now) {
        for (ProductProductionColorDecision color : colors) {
            ProductVariantColor snapshot = new ProductVariantColor();
            snapshot.setProjectProductId(project.getProductId());
            snapshot.setSourceProductId(parent.getProductId());
            snapshot.setSourceDecisionId(color.getProductProductionColorDecisionId());
            snapshot.setCodeItemId(color.getCodeItemId());
            snapshot.setColorCode(color.getColorCode());
            snapshot.setColorName(color.getColorName());
            snapshot.setSourceDecisionBatchNo(color.getDecisionBatchNo());
            snapshot.setSourceConfirmedAt(color.getConfirmedAt());
            snapshot.setDefaultSelectedFlag(1);
            snapshot.setSelectedFlag(1);
            snapshot.setSnapshotStatus("active");
            snapshot.setCreatedAt(now);
            snapshot.setCreatedBy(operator);
            snapshot.setUpdatedAt(now);
            snapshot.setUpdatedBy(operator);
            snapshot.setDeletedFlag(0);
            variantColorRepository.insert(snapshot);
        }
    }

    private ProductProductionColorVO toProductionColorVO(ProductProductionColorDecision color) {
        return ProductProductionColorVO.builder()
            .codeItemId(color.getCodeItemId())
            .colorCode(color.getColorCode())
            .colorName(color.getColorName())
            .confirmedAt(color.getConfirmedAt())
            .build();
    }

    private void ensureEditable(Product product) {
        if ("frozen".equals(product.getLockStatus())) {
            throw new BusinessException(ErrorCodeConstants.VERSION_FROZEN, "当前版本已冻结，请新建版本或发起变更");
        }
        if (ProductStatusConstants.RELEASED.equals(product.getStatus())) {
            throw new BusinessException(ErrorCodeConstants.VERSION_RELEASED, "已发布版本不可直接修改");
        }
    }

    private void validateStatusTransition(String currentStatus, String targetStatus) {
        if (!ProductStatusConstants.TRANSITIONS.getOrDefault(currentStatus, java.util.Set.of()).contains(targetStatus)) {
            throw new BusinessException(
                ErrorCodeConstants.STATUS_TRANSITION_ILLEGAL,
                String.format("不允许从 %s 变更为 %s", currentStatus, targetStatus)
            );
        }
    }

    private void bindWorkflowTemplate(Product product) {
        if (workflowTemplateService == null || product == null || !StringUtils.hasText(product.getProductType())) {
            return;
        }
        WorkflowTemplate template = workflowTemplateService.findActiveTemplate(product.getProductType());
        if (template == null) {
            return;
        }
        product.setWorkflowTemplateId(template.getWorkflowTemplateId());
        product.setWorkflowTemplateVersionNo(template.getVersionNo());
    }

    private ProductVO toVO(Product product) {
        List<ProductMoldCodeVO> moldCodeDetails = productMoldCodeService == null
            ? List.of()
            : productMoldCodeService.listByProductId(product.getProductId());
        RequirementForm requirementForm = findRequirementForm(product);
        return ProductVO.builder()
            .productId(product.getProductId())
            .parentProductId(product.getParentProductId())
            .customerId(product.getCustomerId())
            .productCode(product.getProductCode())
            .productCodePrefix(product.getProductCodePrefix())
            .moldCodePrefix(product.getMoldCodePrefix())
            .moldCodes(moldCodeDetails.stream().map(ProductMoldCodeVO::getMoldCode).filter(StringUtils::hasText).sorted().distinct().reduce((left, right) -> left + "," + right).orElse(null))
            .moldCodeDetails(moldCodeDetails)
            .productSpecificCode(product.getProductSpecificCode())
            .phoneModelCode(product.getPhoneModelCode())
            .phoneModelId(product.getPhoneModelMemberId())
            .colorCode(product.getColorCode())
            .finishedProductCode(product.getFinishedProductCode())
            .importShortCode(product.getImportShortCode())
            .productName(product.getProductName())
            .productType(product.getProductType())
            .developmentMode(product.getDevelopmentMode())
            .seriesName(product.getSeriesName())
            .model(product.getModel())
            .color(product.getColor())
            .material(product.getMaterial())
            .packageType(product.getPackageType())
            .surfaceProcess(product.getSurfaceProcess())
            .coreProcess(product.getCoreProcess())
            .composition(product.getComposition())
            .ownerUserId(product.getOwnerUserId())
            .versionNo(product.getVersionNo())
            .status(product.getStatus())
            .expectedDeliveryDate(product.getExpectedDeliveryDate())
            .productionStatus(com.yuewei.plm.module.productionmetadata.ProductionStatus.display(product.getProductionStatus()))
            .moldTransferAt(product.getMoldTransferAt())
            .expectedArrivalAt(product.getExpectedArrivalAt())
            .actualArrivalAt(product.getActualArrivalAt())
            .networkType(requirementForm == null ? null : requirementForm.getNetworkType())
            .holeType(requirementForm == null ? null : requirementForm.getHoleType())
            .mobileFunction(requirementForm == null ? null : requirementForm.getMobileFunction())
            .tipo(requirementForm == null ? null : requirementForm.getTipo())
            .priority(requirementForm == null ? null : requirementForm.getPriority())
            .manufacturingLocation(requirementForm == null ? null : requirementForm.getManufacturingLocation())
            .moldMarking(requirementForm == null ? null : requirementForm.getMoldMarking())
            .referenceUrl(requirementForm == null ? product.getSourceFormUrl() : requirementForm.getReferenceUrl())
            .requirementType(requirementForm == null ? null : requirementForm.getRequirementType())
            .customerRequirement(requirementForm == null ? null : requirementForm.getCustomerRequirement())
            .currentStepNo(product.getCurrentStepNo())
            .effectiveDate(product.getEffectiveDate())
            .releasedAt(product.getReleasedAt())
            .releasedBy(product.getReleasedBy())
            .frozenAt(product.getFrozenAt())
            .frozenBy(product.getFrozenBy())
            .freezeReason(product.getFreezeReason())
            .archivedAt(product.getArchivedAt())
            .archivedBy(product.getArchivedBy())
            .archiveReason(product.getArchiveReason())
            .abandonedAt(product.getAbandonedAt())
            .abandonedBy(product.getAbandonedBy())
            .abandonReason(product.getAbandonReason())
            .lockStatus(product.getLockStatus())
            .remark(product.getRemark())
            .createdAt(product.getCreatedAt())
            .createdBy(product.getCreatedBy())
            .updatedAt(product.getUpdatedAt())
            .updatedBy(product.getUpdatedBy())
            .build();
    }

    private RequirementForm findRequirementForm(Product product) {
        if (requirementFormRepository == null || product == null || !"model_variant".equals(product.getProductType())) {
            return null;
        }
        return requirementFormRepository.selectList(new LambdaQueryWrapper<RequirementForm>()
                .eq(RequirementForm::getProjectId, product.getProductId())
                .eq(RequirementForm::getDeletedFlag, 0))
            .stream()
            .findFirst()
            .orElse(null);
    }

    private void applyBusinessCodesForCreate(Product product, ProductCreateDTO dto, Product parent,
                                             List<ProductProductionColorDecision> inheritedColors) {
        product.setProductSpecificCode(normalizeCompactCode(firstText(
            dto.getProductSpecificCode(),
            parent == null ? null : parent.getProductSpecificCode(),
            parent == null ? null : parent.getProductCodePrefix(),
            product.getProductCodePrefix()
        )));
        product.setPhoneModelCode(normalizeCompactCode(dto.getPhoneModelCode()));
        if(masterDataJdbc!=null&&dto.getPhoneModelId()==null)com.yuewei.plm.module.masterdata.MasterDataAdoption.requireCode(masterDataJdbc,"phone_model",product.getPhoneModelCode());
        String inheritedSingleColorCode = inheritedColors != null && inheritedColors.size() == 1
            ? inheritedColors.get(0).getColorCode()
            : null;
        product.setColorCode(normalizeCompactCode(firstText(dto.getColorCode(), inheritedSingleColorCode)));
        if(masterDataJdbc!=null)com.yuewei.plm.module.masterdata.MasterDataAdoption.requireCode(masterDataJdbc,"color",product.getColorCode());
        product.setFinishedProductCode(resolveFinishedProductCode(dto.getFinishedProductCode(), product));
        product.setImportShortCode(normalizeCode(dto.getImportShortCode()));
    }
    private Map<String,Object> validateSelectedPhone(ProductCreateDTO dto) {
        if(dto.getPhoneModelId()==null)return null;
        if(!"model_variant".equals(dto.getProductType()))throw new BusinessException(40001,"标准手机成员仅用于新型号项目");
        if(phoneModelReferences==null||pilotAccessService==null)throw new BusinessException(40001,"标准手机资料暂不可用，不能绕过关联校验");
        if(dto.getPhoneModelCode()==null)throw new BusinessException(40001,"所选手机原编码待核对，不能从型号文字猜码立项");
        var member=phoneModelReferences.requireSelectedPhone(dto.getPhoneModelId(),null,dto.getPhoneModelCode(),dto.getModel());
        dto.setCreatedBy(pilotAccessService.actor().username());return member;
    }

    private void validateStablePhoneIdentity(Product product, ProductUpdateDTO dto) {
        if (product.getPhoneModelMemberId() == null) return;
        if (StringUtils.hasText(dto.getModel()) && !java.util.Objects.equals(product.getModel(), dto.getModel()))
            throw new BusinessException(40001, "已有项目的标准手机身份和原型号快照不可在基础编辑中改绑，请正式核对关联");
        if (dto.getPhoneModelCode() != null && !java.util.Objects.equals(product.getPhoneModelCode(), normalizeCompactCode(dto.getPhoneModelCode())))
            throw new BusinessException(40001, "已有项目的标准手机原编码不可在基础编辑中换码，请正式核对关联");
    }

    private void applyBusinessCodesForUpdate(Product product, ProductUpdateDTO dto) {
        if (dto.getProductSpecificCode() != null) {
            String next=normalizeCompactCode(dto.getProductSpecificCode());
            if(com.yuewei.plm.module.project.development.ProductDevelopmentService.isLaunch(product)&&!java.util.Objects.equals(product.getProductSpecificCode(),next)){
                if(StringUtils.hasText(product.getProductSpecificCode()))throw new BusinessException(40001,"已有新品产品特定编码不可在基础编辑中改写，请保留原编码");
                if(next==null||!next.matches("[A-Z]{2}"))throw new BusinessException(40001,"请填写两位大写英文字母作为产品特定编码，不能自动猜码");
                if(pilotAccessService==null)throw new BusinessException(40001,"工程授权暂不可用，请稍后重试");
                pilotAccessService.requireEngineeringMaintenance();
            }
            product.setProductSpecificCode(next);
        }
        if (dto.getPhoneModelCode() != null) {
            String next=normalizeCompactCode(dto.getPhoneModelCode());
            if(masterDataJdbc!=null&&!java.util.Objects.equals(next,product.getPhoneModelCode()))com.yuewei.plm.module.masterdata.MasterDataAdoption.requireCode(masterDataJdbc,"phone_model",next);
            product.setPhoneModelCode(next);
        }
        if (dto.getColorCode() != null) {
            String next=normalizeCompactCode(dto.getColorCode());
            if(masterDataJdbc!=null&&!java.util.Objects.equals(next,product.getColorCode()))com.yuewei.plm.module.masterdata.MasterDataAdoption.requireCode(masterDataJdbc,"color",next);
            product.setColorCode(next);
        }
        if (dto.getFinishedProductCode() != null) {
            product.setFinishedProductCode(normalizeCode(dto.getFinishedProductCode()));
        }
        if (dto.getImportShortCode() != null) {
            product.setImportShortCode(normalizeCode(dto.getImportShortCode()));
        }
        if (!StringUtils.hasText(product.getFinishedProductCode())) {
            product.setFinishedProductCode(resolveFinishedProductCode(null, product));
        }
    }

    private void auditInitialResearchCode(Product product,String previous){
        if(!com.yuewei.plm.module.project.development.ProductDevelopmentService.isLaunch(product)||StringUtils.hasText(previous)||!StringUtils.hasText(product.getProductSpecificCode()))return;
        var detail=new LinkedHashMap<String,Object>();detail.put("before",previous);detail.put("productSpecificCode",product.getProductSpecificCode());detail.put("reason","工程首次登记新品产品特定编码，沿用原产品档案");
        try{operationLogService.logRequired(OperationLogCreateCommand.builder().action("PRODUCT_BUSINESS_IDENTITY_ASSIGN").businessType("PRODUCT").businessId(String.valueOf(product.getProductId())).businessCode(product.getProductCode()).businessName(product.getProductName()).detailJson(objectMapper.writeValueAsString(detail)).build(),pilotAccessService.actor());}
        catch(JsonProcessingException failure){throw new IllegalStateException("新品编码登记审计失败",failure);}
    }

    private String resolveFinishedProductCode(String explicitCode, Product product) {
        if (StringUtils.hasText(explicitCode)) {
            return normalizeCode(explicitCode);
        }
        if (!List.of("model_variant", "sku").contains(product.getProductType())) {
            return null;
        }
        if (!StringUtils.hasText(product.getProductSpecificCode())
            || !StringUtils.hasText(product.getPhoneModelCode())
            || !StringUtils.hasText(product.getColorCode())) {
            return null;
        }
        return businessCodeGenerator.generateProductStateCode(
            product.getProductSpecificCode(),
            ProductBusinessCodeGenerator.DEFAULT_FINAL_OPERATION_CODE,
            product.getPhoneModelCode(),
            product.getColorCode()
        );
    }

    private String resolveProductLineProductCode(String explicitCode, String productSpecificCode) {
        String normalizedExplicit = normalizeCompactCode(explicitCode);
        String normalizedProduct = normalizeCompactCode(productSpecificCode);
        String explicitProductLineCode = normalizeProductLineCodeCandidate(normalizedExplicit, normalizedProduct);
        if (StringUtils.hasText(explicitProductLineCode)) {
            return explicitProductLineCode;
        }
        if (!StringUtils.hasText(normalizedProduct)) {
            return null;
        }
        return businessCodeGenerator.generateProductLineCode(
            normalizedProduct,
            ProductBusinessCodeGenerator.DEFAULT_FINAL_OPERATION_CODE
        );
    }

    private String normalizeProductLineCodeCandidate(String value, String expectedProductSpecificCode) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String body = value.startsWith(ProductBusinessCodeGenerator.PRODUCT_STATE_PREFIX)
            ? value.substring(ProductBusinessCodeGenerator.PRODUCT_STATE_PREFIX.length())
            : value;
        if (body.length() <= 4 || !body.substring(body.length() - 4).matches("\\d{4}")) {
            return null;
        }
        String product = body.substring(0, body.length() - 4);
        if (StringUtils.hasText(expectedProductSpecificCode) && !expectedProductSpecificCode.equals(product)) {
            return null;
        }
        return businessCodeGenerator.generateProductLineCode(product, body.substring(body.length() - 4));
    }

    private void ensureProductBusinessCodeAvailable(Product product, Long excludedProductId) {
        if (StringUtils.hasText(product.getProductSpecificCode()) && "product_line".equals(product.getProductType())) {
            if (masterDataJdbc != null && org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                masterDataJdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, "product-specific:" + product.getProductSpecificCode());
            ensureProductFieldAvailable(Product::getProductSpecificCode, product.getProductSpecificCode(), excludedProductId,
                "productSpecificCode already exists: " + product.getProductSpecificCode());
        }
        if (StringUtils.hasText(product.getFinishedProductCode())) {
            ensureProductFieldAvailable(Product::getFinishedProductCode, product.getFinishedProductCode(), excludedProductId,
                "finishedProductCode already exists: " + product.getFinishedProductCode());
        }
        if (product.getParentProductId() != null
            && List.of("model_variant", "sku").contains(product.getProductType())
            && StringUtils.hasText(product.getPhoneModelCode())
            && StringUtils.hasText(product.getColorCode())) {
            LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<Product>()
                .eq(Product::getParentProductId, product.getParentProductId())
                .eq(Product::getPhoneModelCode, product.getPhoneModelCode())
                .eq(Product::getColorCode, product.getColorCode())
                .eq(Product::getDeletedFlag, 0);
            if (excludedProductId != null) {
                wrapper.ne(Product::getProductId, excludedProductId);
            }
            Long count = productRepository.selectCount(wrapper);
            if (count != null && count > 0) {
                throw new BusinessException(ErrorCodeConstants.CODE_CONFLICT, "phoneModelCode and colorCode already exist under parent product");
            }
        }
    }

    private void ensureProductFieldAvailable(com.baomidou.mybatisplus.core.toolkit.support.SFunction<Product, ?> column,
                                             String value, Long excludedProductId, String message) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<Product>()
            .eq(column, value); // Stable business codes remain reserved by historical tombstones.
        if (excludedProductId != null) {
            wrapper.ne(Product::getProductId, excludedProductId);
        }
        Long count = productRepository.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCodeConstants.CODE_CONFLICT, message);
        }
    }

    private String normalizeCode(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase() : null;
    }

    private String normalizeCompactCode(String value) {
        String normalized = normalizeCode(value);
        return StringUtils.hasText(normalized) ? normalized.replace("-", "") : null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}
