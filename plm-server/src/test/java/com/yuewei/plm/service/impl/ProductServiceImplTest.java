package com.yuewei.plm.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.yuewei.plm.common.config.JacksonConfig;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.security.CurrentUserContext;
import com.yuewei.plm.common.util.ProductCodeGenerator;
import com.yuewei.plm.controller.dto.ProductCreateDTO;
import com.yuewei.plm.controller.dto.ProductUpdateDTO;
import com.yuewei.plm.module.bom.entity.ProductProductionColorDecision;
import com.yuewei.plm.module.bom.repository.ProductProductionColorDecisionRepository;
import com.yuewei.plm.module.bom.service.BomInheritanceService;
import com.yuewei.plm.module.operationlog.constant.OperationActionConstants;
import com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.process.service.ProcessRouteInheritanceService;
import com.yuewei.plm.module.project.variant.entity.ProductVariantColor;
import com.yuewei.plm.module.project.variant.repository.ProductVariantColorRepository;
import com.yuewei.plm.repository.ProductRepository;
import com.yuewei.plm.repository.entity.Product;
import com.yuewei.plm.service.ProductReleaseGateValidator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ProductServiceImplTest {

    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
    }

    private record ResearchIdentityFixture(ProductServiceImpl service,ProductRepository products,Product product,
                                           OperationLogService audit,com.yuewei.plm.module.pilot.PilotAccessService access,
                                           com.yuewei.plm.module.project.development.ProductDevelopmentService development) {}
    private ResearchIdentityFixture researchIdentityFixture(String code) {
        var products=mock(ProductRepository.class);var audit=mock(OperationLogService.class);
        var access=mock(com.yuewei.plm.module.pilot.PilotAccessService.class);
        var development=mock(com.yuewei.plm.module.project.development.ProductDevelopmentService.class);
        var product=new Product();product.setProductId(42L);product.setProductCode("PRD-RESEARCH-0042");
        product.setProductName("透甲2.0");product.setProductType("product_line");product.setDevelopmentMode("launch_v1");
        product.setProductSpecificCode(code);product.setStatus("developing");product.setDeletedFlag(0);product.setCurrentStepNo(4);
        product.setExpectedDeliveryDate(LocalDate.of(2026,11,5));product.setExpectedArrivalAt(LocalDateTime.of(2026,11,1,9,30));
        product.setActualArrivalAt(LocalDateTime.of(2026,10,23,10,15));product.setRemark("保留研发原说明与计划");
        when(products.selectById(42L)).thenReturn(product);when(products.selectIdentityForUpdate(42L)).thenReturn(product);when(development.requireEditable(42L)).thenReturn(product);
        var actor=new CurrentUser(7L,"real-engineer","真实工程账号",false);when(access.actor()).thenReturn(actor);when(access.requireEngineeringMaintenance()).thenReturn(actor);
        var service=new ProductServiceImpl(products,mock(ProductCodeGenerator.class),audit,new JacksonConfig().objectMapper(),
            mock(ProductReleaseGateValidator.class),mock(BomInheritanceService.class),mock(ProcessRouteInheritanceService.class),
            mock(ProductProductionColorDecisionRepository.class),mock(ProductVariantColorRepository.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"pilotAccessService",access);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"productDevelopmentService",development);
        return new ResearchIdentityFixture(service,products,product,audit,access,development);
    }
    private ProductUpdateDTO researchIdentityDto(String code) {
        var dto=new ProductUpdateDTO();dto.setUpdatedBy("client-claimed-updater");dto.setProductSpecificCode(code);return dto;
    }

    @Test void automaticResearchIdentityUsesTheFirstUnusedPrefixAndPreservesTheOriginalProjectAndStage() {
        var f=researchIdentityFixture(null);
        var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);
        org.springframework.test.util.ReflectionTestUtils.setField(f.service(),"masterDataJdbc",jdbc);
        when(jdbc.queryForList(Mockito.anyString(),Mockito.eq(String.class))).thenReturn(List.of("AA","AB","XY"));
        when(jdbc.queryForObject(Mockito.anyString(),Mockito.eq(Long.class),Mockito.any())).thenReturn(0L);
        assertThat(f.service().ensureResearchProductSpecificCode(42L,"real-engineer")).isEqualTo("AC");
        assertThat(f.product().getProductCode()).isEqualTo("PRD-RESEARCH-0042");
        assertThat(f.product().getProductCodePrefix()).isEqualTo("AC");
        assertThat(f.product().getMoldCodePrefix()).isEqualTo("MAC");
        assertThat(f.product().getStatus()).isEqualTo("developing");
        assertThat(f.product().getCurrentStepNo()).isEqualTo(4);
        verify(f.products()).updateById(f.product());
        verify(f.audit()).logRequired(Mockito.any(),Mockito.eq(new CurrentUser(7L,"real-engineer","真实工程账号",false)));
        assertThat(f.service().ensureResearchProductSpecificCode(42L,"real-engineer")).isEqualTo("AC");
        verify(f.products()).updateById(f.product());
    }

    @Test void automaticResearchIdentityNeverReplacesAnExistingCodeOrAllocatesToAFrozenProject() {
        var existing=researchIdentityFixture("BA");
        assertThat(existing.service().ensureResearchProductSpecificCode(42L,"engineer")).isEqualTo("BA");
        verify(existing.products(),never()).updateById(Mockito.any(Product.class));
        var frozen=researchIdentityFixture(null);frozen.product().setLockStatus("frozen");
        assertThatThrownBy(()->frozen.service().ensureResearchProductSpecificCode(42L,"engineer")).hasMessageContaining("冻结");
        verify(frozen.products(),never()).updateById(Mockito.any(Product.class));
    }

    @Test void releasedResearchCanFillItsEmptyCodeThroughBothOriginalWritersWithoutEditingTechnicalFacts() {
        for(boolean basic:List.of(false,true)){
            var f=researchIdentityFixture(null);f.product().setStatus("released");f.product().setLockStatus("unlocked");
            var result=basic?f.service().updateBasicInfo(42L,researchIdentityDto("TG")):f.service().update(42L,researchIdentityDto("TG"));
            assertThat(result.getProductSpecificCode()).isEqualTo("TG");assertThat(result.getStatus()).isEqualTo("released");assertThat(result.getProductCode()).isEqualTo("PRD-RESEARCH-0042");
            assertThat(f.product().getUpdatedBy()).isEqualTo("real-engineer");assertThat(f.product().getRemark()).isEqualTo("保留研发原说明与计划");assertThat(f.product().getCurrentStepNo()).isEqualTo(4);
            verify(f.products()).selectIdentityForUpdate(42L);verify(f.development(),never()).requireEditable(Mockito.anyLong());verify(f.access()).requirePermission("documents:write");verify(f.access()).requirePermission("product:write");verify(f.access()).requireProduct(42L);verify(f.audit()).logRequired(Mockito.any(),Mockito.eq(new CurrentUser(7L,"real-engineer","真实工程账号",false)));
        }
    }
    @Test void releasedIdentityCompletionRejectsOtherPayloadFieldsIncludingEmptyValues() {
        for(String value:List.of("夹带的技术修改","")){
            var f=researchIdentityFixture(null);f.product().setStatus("released");var dto=researchIdentityDto("TG");dto.setRemark(value);
            assertThatThrownBy(()->f.service().update(42L,dto)).hasMessageContaining("仅允许首次补填");assertThatThrownBy(()->f.service().updateBasicInfo(42L,dto)).hasMessageContaining("仅允许首次补填");
            verify(f.products(),never()).updateById(Mockito.any(Product.class));assertThat(f.product().getProductSpecificCode()).isNull();
        }
    }
    @Test void releasedIdentityCompletionStillRejectsFrozenStoppedOrAlreadyAssignedRecords() {
        for(String lock:List.of("frozen","abandoned")){
            var f=researchIdentityFixture(null);f.product().setStatus("released");f.product().setLockStatus(lock);assertThatThrownBy(()->f.service().update(42L,researchIdentityDto("TG"))).hasMessageContaining("冻结或停止");verify(f.products(),never()).updateById(Mockito.any(Product.class));
        }
        var f=researchIdentityFixture("TG");f.product().setStatus("released");assertThatThrownBy(()->f.service().update(42L,researchIdentityDto("XX"))).hasMessageContaining("原编码");assertThatThrownBy(()->f.service().updateBasicInfo(42L,researchIdentityDto("TG"))).hasMessageContaining("原编码");verify(f.products(),never()).updateById(Mockito.any(Product.class));
    }
    @Test void releasedIdentityCompletionRequiresDocumentPermissionBeforeLockingOrWriting() {
        var f=researchIdentityFixture(null);f.product().setStatus("released");Mockito.doThrow(new BusinessException(40301,"没有文档写入权限")).when(f.access()).requirePermission("documents:write");
        assertThatThrownBy(()->f.service().update(42L,researchIdentityDto("TG"))).hasMessageContaining("文档写入权限");verify(f.products(),never()).selectIdentityForUpdate(Mockito.anyLong());verify(f.products(),never()).updateById(Mockito.any(Product.class));
    }
    @Test void releasedIdentityCompletionRechecksLifecycleAfterAcquiringTheRowLock() {
        var f=researchIdentityFixture(null);f.product().setStatus("released");var current=new Product();current.setProductType("product_line");current.setDevelopmentMode("launch_v1");current.setStatus("archived");when(f.products().selectIdentityForUpdate(42L)).thenReturn(current);
        assertThatThrownBy(()->f.service().update(42L,researchIdentityDto("TG"))).hasMessageContaining("状态已变化");verify(f.products(),never()).updateById(Mockito.any(Product.class));
    }

    @Test void initialResearchProductCodeUsesTheExistingWriterAndRequiredRealActorAudit() {
        var f=researchIdentityFixture(null);
        var result=f.service().update(42L,researchIdentityDto("TG"));
        assertThat(result.getProductSpecificCode()).isEqualTo("TG");assertThat(result.getProductCode()).isEqualTo("PRD-RESEARCH-0042");
        assertThat(result.getDevelopmentMode()).isEqualTo("launch_v1");assertThat(f.product().getFinishedProductCode()).isNull();
        verify(f.development()).requireEditable(42L);verify(f.access()).requireEngineeringMaintenance();verify(f.products()).updateById(f.product());
        var log=ArgumentCaptor.forClass(OperationLogCreateCommand.class);
        verify(f.audit()).logRequired(log.capture(),Mockito.eq(new CurrentUser(7L,"real-engineer","真实工程账号",false)));
        assertThat(log.getValue().getAction()).isEqualTo("PRODUCT_BUSINESS_IDENTITY_ASSIGN");
        assertThat(log.getValue().getBusinessId()).isEqualTo("42");
        assertThat(log.getValue().getDetailJson()).contains("\"before\":null","\"productSpecificCode\":\"TG\"","工程首次登记");
        verify(f.products(),never()).insert(Mockito.any(Product.class));
    }

    @Test void partialResearchIdentityFillPreservesDatesRemarkAndCurrentStage() {
        var f=researchIdentityFixture(null);
        f.service().update(42L,researchIdentityDto("TG"));
        assertThat(f.product().getExpectedDeliveryDate()).isEqualTo(LocalDate.of(2026,11,5));
        assertThat(f.product().getExpectedArrivalAt()).isEqualTo(LocalDateTime.of(2026,11,1,9,30));
        assertThat(f.product().getActualArrivalAt()).isEqualTo(LocalDateTime.of(2026,10,23,10,15));
        assertThat(f.product().getRemark()).isEqualTo("保留研发原说明与计划");assertThat(f.product().getCurrentStepNo()).isEqualTo(4);
    }

    @Test void existingResearchCodeCannotBeReplacedThroughEitherProductUpdateEntry() {
        var f=researchIdentityFixture("TG");var dto=researchIdentityDto("XX");
        assertThatThrownBy(()->f.service().update(42L,dto)).isInstanceOf(BusinessException.class).hasMessageContaining("原编码");
        assertThatThrownBy(()->f.service().updateBasicInfo(42L,dto)).isInstanceOf(BusinessException.class).hasMessageContaining("原编码");
        assertThat(f.product().getProductSpecificCode()).isEqualTo("TG");assertThat(f.product().getProductCode()).isEqualTo("PRD-RESEARCH-0042");
        verify(f.products(),never()).updateById(Mockito.any(Product.class));verify(f.audit(),never()).logRequired(Mockito.any(),Mockito.any());
    }

    @Test void initialResearchCodeRequiresEngineeringResponsibilityBeforeAnyWrite() {
        var f=researchIdentityFixture(null);
        Mockito.doThrow(new BusinessException(40301,"当前账号未分配工程职责")).when(f.access()).requireEngineeringMaintenance();
        assertThatThrownBy(()->f.service().update(42L,researchIdentityDto("TG"))).isInstanceOf(BusinessException.class).hasMessageContaining("工程职责");
        assertThat(f.product().getProductSpecificCode()).isNull();verify(f.products(),never()).updateById(Mockito.any(Product.class));
        verify(f.audit(),never()).logRequired(Mockito.any(),Mockito.any());
    }

    @Test void requiredResearchIdentityAuditFailurePropagatesInsteadOfReportingSuccessfulAssignment() {
        var f=researchIdentityFixture(null);
        Mockito.doThrow(new IllegalStateException("必需审计不可用")).when(f.audit()).logRequired(Mockito.any(),Mockito.any());
        assertThatThrownBy(()->f.service().update(42L,researchIdentityDto("TG"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("审计不可用");
        verify(f.products()).updateById(f.product());
    }

    @Test
    void freezeUsesCurrentUserAndWritesProductFreezeOperationLog() {
        ProductRepository productRepository = mock(ProductRepository.class);
        ProductCodeGenerator productCodeGenerator = mock(ProductCodeGenerator.class);
        OperationLogService operationLogService = mock(OperationLogService.class);
        ProductServiceImpl service = new ProductServiceImpl(
            productRepository,
            productCodeGenerator,
            operationLogService,
            new JacksonConfig().objectMapper(),
            mock(ProductReleaseGateValidator.class),
            mock(BomInheritanceService.class),
            mock(ProcessRouteInheritanceService.class),
            mock(ProductProductionColorDecisionRepository.class),
            mock(ProductVariantColorRepository.class)
        );
        Product product = new Product();
        product.setProductId(100L);
        product.setProductCode("PRD-CD30-IP18-BLK-A");
        product.setProductName("超队 3.0 iPhone18 黑色");
        product.setStatus("draft");
        product.setDeletedFlag(0);
        when(productRepository.selectById(100L)).thenReturn(product);
        CurrentUserContext.set(new CurrentUser(1L, "engineer01", "工程部用户一", true));
        HttpServletRequest request = mock(HttpServletRequest.class);
        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        ArgumentCaptor<OperationLogCreateCommand> logCaptor = ArgumentCaptor.forClass(OperationLogCreateCommand.class);

        service.freeze(100L, "M1操作人验证", request);

        verify(productRepository).updateById(productCaptor.capture());
        Product frozen = productCaptor.getValue();
        assertThat(frozen.getFrozenBy()).isEqualTo("工程部用户一");
        assertThat(frozen.getLockOperatorUserId()).isEqualTo(1L);
        assertThat(frozen.getLockOperatorUserName()).isEqualTo("工程部用户一");
        assertThat(frozen.getUpdatedBy()).isEqualTo("工程部用户一");

        verify(operationLogService).logSuccess(logCaptor.capture());
        OperationLogCreateCommand command = logCaptor.getValue();
        assertThat(command.getAction()).isEqualTo(OperationActionConstants.PRODUCT_FREEZE);
        assertThat(command.getBusinessType()).isEqualTo("PRODUCT");
        assertThat(command.getBusinessId()).isEqualTo("100");
        assertThat(command.getBusinessCode()).isEqualTo("PRD-CD30-IP18-BLK-A");
        assertThat(command.getBusinessName()).isEqualTo("超队 3.0 iPhone18 黑色");
        assertThat(command.getDetailJson()).contains("M1操作人验证");
        assertThat(command.getRequest()).isSameAs(request);
    }

    @Test
    void getByIdMapsCurrentStepNo() {
        ProductRepository productRepository = mock(ProductRepository.class);
        ProductCodeGenerator productCodeGenerator = mock(ProductCodeGenerator.class);
        OperationLogService operationLogService = mock(OperationLogService.class);
        ProductServiceImpl service = new ProductServiceImpl(
            productRepository,
            productCodeGenerator,
            operationLogService,
            new JacksonConfig().objectMapper(),
            mock(ProductReleaseGateValidator.class),
            mock(BomInheritanceService.class),
            mock(ProcessRouteInheritanceService.class),
            mock(ProductProductionColorDecisionRepository.class),
            mock(ProductVariantColorRepository.class)
        );
        Product product = new Product();
        product.setProductId(101L);
        product.setProductCode("PRD-CD30-0002");
        product.setProductName("超队 3.0");
        product.setProductType("product_line");
        product.setStatus("developing");
        product.setCurrentStepNo(3);
        product.setDeletedFlag(0);
        when(productRepository.selectById(101L)).thenReturn(product);

        var vo = service.getById(101L);

        assertThat(vo.getCurrentStepNo()).isEqualTo(3);
    }

    @Test
    void updateBasicInfoAllowsReleasedProductWithoutChangingVersionFields() {
        ProductRepository productRepository = mock(ProductRepository.class);
        ProductServiceImpl service = new ProductServiceImpl(
            productRepository,
            mock(ProductCodeGenerator.class),
            mock(OperationLogService.class),
            new JacksonConfig().objectMapper(),
            mock(ProductReleaseGateValidator.class),
            mock(BomInheritanceService.class),
            mock(ProcessRouteInheritanceService.class),
            mock(ProductProductionColorDecisionRepository.class),
            mock(ProductVariantColorRepository.class)
        );
        Product product = new Product();
        product.setProductId(102L);
        product.setProductCode("PRD-CD30-0003");
        product.setProductName("旧名称");
        product.setProductType("product_line");
        product.setVersionNo("A");
        product.setStatus("released");
        product.setLockStatus("frozen");
        product.setDeletedFlag(0);
        when(productRepository.selectById(102L)).thenReturn(product);
        ProductUpdateDTO dto = new ProductUpdateDTO();
        dto.setUpdatedBy("pm01");
        dto.setProductName("发布后基础信息名称");
        dto.setExpectedDeliveryDate(LocalDate.of(2026, 8, 20));

        var result = service.updateBasicInfo(102L, dto);

        verify(productRepository).updateById(product);
        assertThat(result.getProductName()).isEqualTo("发布后基础信息名称");
        assertThat(result.getStatus()).isEqualTo("released");
        assertThat(result.getVersionNo()).isEqualTo("A");
        assertThat(result.getExpectedDeliveryDate()).isEqualTo(LocalDate.of(2026, 8, 20));
    }

    @Test
    void creatingModelVariantInheritsConfirmedProductionColorsAndIgnoresFrontendColor() {
        ProductRepository repository = mock(ProductRepository.class);
        BomInheritanceService inheritance = mock(BomInheritanceService.class);
        ProductProductionColorDecisionRepository colorRepository = mock(ProductProductionColorDecisionRepository.class);
        ProductVariantColorRepository variantColorRepository = mock(ProductVariantColorRepository.class);
        ProductCodeGenerator generator = mock(ProductCodeGenerator.class);
        ProcessRouteInheritanceService processInheritance = mock(ProcessRouteInheritanceService.class);
        when(generator.generate("亮甲")).thenReturn("PRD-LJ-IP18");
        Product parent = new Product();
        parent.setProductId(5L);
        parent.setProductType("product_line");
        parent.setSeriesName("亮甲 3.0");
        parent.setStatus("released");
        parent.setDeletedFlag(0);
        when(repository.selectById(5L)).thenReturn(parent);
        when(colorRepository.selectList(Mockito.<Wrapper<ProductProductionColorDecision>>any())).thenReturn(List.of(
            color(10L, "01", "黑色"),
            color(11L, "02", "透明色")
        ));
        org.mockito.Mockito.doAnswer(invocation -> {
            Product created = invocation.getArgument(0);
            created.setProductId(20L);
            return 1;
        }).when(repository).insert(org.mockito.ArgumentMatchers.any(Product.class));
        when(processInheritance.inheritLatestReleasedFormalBomRoutesByColors(Mockito.eq(5L), Mockito.any(Product.class),
            Mockito.eq(List.of("黑色", "透明色")), Mockito.eq("engineer"))).thenReturn(Map.of(27L, 200L));
        ProductServiceImpl service = new ProductServiceImpl(repository, generator, mock(OperationLogService.class),
            new JacksonConfig().objectMapper(), mock(ProductReleaseGateValidator.class), inheritance,
            processInheritance, colorRepository, variantColorRepository);
        ProductCreateDTO dto = new ProductCreateDTO();
        dto.setParentProductId(5L); dto.setProductName("亮甲"); dto.setProductType("model_variant");
        dto.setModel("iPhone 18"); dto.setColor("前端手填颜色"); dto.setVersionNo("V1"); dto.setCreatedBy("engineer");

        org.springframework.test.util.ReflectionTestUtils.setField(service,"productSalesPolicy",mock(com.yuewei.plm.module.productionmetadata.ProductSalesPolicy.class));
        service.create(dto);

        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(repository).insert(productCaptor.capture());
        assertThat(productCaptor.getValue().getSeriesName()).isEqualTo("亮甲 3.0");
        assertThat(productCaptor.getValue().getColor()).isNull();

        ArgumentCaptor<ProductVariantColor> colorCaptor = ArgumentCaptor.forClass(ProductVariantColor.class);
        verify(variantColorRepository, Mockito.times(2)).insert(colorCaptor.capture());
        assertThat(colorCaptor.getAllValues()).extracting(ProductVariantColor::getColorName)
            .containsExactly("黑色", "透明色");
        assertThat(colorCaptor.getAllValues()).allMatch(value ->
            value.getProjectProductId().equals(20L)
                && value.getSourceProductId().equals(5L)
                && Integer.valueOf(1).equals(value.getDefaultSelectedFlag())
                && Integer.valueOf(1).equals(value.getSelectedFlag())
                && "active".equals(value.getSnapshotStatus())
        );

        verify(processInheritance).inheritLatestReleasedFormalBomRoutesByColors(Mockito.eq(5L), Mockito.any(Product.class),
            Mockito.eq(List.of("黑色", "透明色")), Mockito.eq("engineer"));
        verify(inheritance).inheritLatestReleasedByColors(5L, 20L, List.of("黑色", "透明色"), Map.of(27L, 200L));
    }

    @Test
    void creatingProductLineUsesProductLineBusinessCodeWhenSpecificCodeExists() {
        ProductRepository repository = mock(ProductRepository.class);
        ProductCodeGenerator generator = mock(ProductCodeGenerator.class);
        when(generator.generate("超队 4.0")).thenReturn("PRD-SUPER-0001");
        ProductServiceImpl service = new ProductServiceImpl(repository, generator, mock(OperationLogService.class),
            new JacksonConfig().objectMapper(), mock(ProductReleaseGateValidator.class), mock(BomInheritanceService.class),
            mock(ProcessRouteInheritanceService.class), mock(ProductProductionColorDecisionRepository.class),
            mock(ProductVariantColorRepository.class));
        ProductCreateDTO dto = new ProductCreateDTO();
        dto.setProductName("超队 4.0");
        dto.setProductType("product_line");
        dto.setProductSpecificCode("HA");
        dto.setSeriesName("超队");
        dto.setVersionNo("A");
        dto.setCreatedBy("engineer");

        org.springframework.test.util.ReflectionTestUtils.setField(service,"productSalesPolicy",mock(com.yuewei.plm.module.productionmetadata.ProductSalesPolicy.class));
        service.create(dto);

        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(repository).insert(productCaptor.capture());
        assertThat(productCaptor.getValue().getProductCode()).isEqualTo("NHA4030");
        assertThat(productCaptor.getValue().getProductSpecificCode()).isEqualTo("HA");
        assertThat(productCaptor.getValue().getFinishedProductCode()).isNull();
    }

    @Test
    void creatingModelVariantRejectsWhenParentHasNoConfirmedColors() {
        ProductRepository repository = mock(ProductRepository.class);
        ProductProductionColorDecisionRepository colorRepository = mock(ProductProductionColorDecisionRepository.class);
        ProductVariantColorRepository variantColorRepository = mock(ProductVariantColorRepository.class);
        Product parent = new Product();
        parent.setProductId(5L);
        parent.setProductType("product_line");
        parent.setStatus("released");
        parent.setDeletedFlag(0);
        when(repository.selectById(5L)).thenReturn(parent);
        when(colorRepository.selectList(Mockito.<Wrapper<ProductProductionColorDecision>>any())).thenReturn(List.of());
        BomInheritanceService inheritance = mock(BomInheritanceService.class);
        ProductServiceImpl service = new ProductServiceImpl(repository, mock(ProductCodeGenerator.class), mock(OperationLogService.class),
            new JacksonConfig().objectMapper(), mock(ProductReleaseGateValidator.class), inheritance,
            mock(ProcessRouteInheritanceService.class), colorRepository, variantColorRepository);
        ProductCreateDTO dto = new ProductCreateDTO();
        dto.setParentProductId(5L); dto.setProductName("亮甲"); dto.setProductType("model_variant");
        dto.setModel("iPhone 18"); dto.setVersionNo("V1"); dto.setCreatedBy("engineer");
        org.springframework.test.util.ReflectionTestUtils.setField(service,"productSalesPolicy",mock(com.yuewei.plm.module.productionmetadata.ProductSalesPolicy.class));
        assertThatThrownBy(() -> service.create(dto))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("尚未敲定正式投产颜色");

        verify(repository, never()).insert(Mockito.any(Product.class));
        verify(inheritance, never()).inheritLatestReleasedByColors(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyList(), Mockito.anyMap());
    }

    private ProductProductionColorDecision color(Long id, String code, String name) {
        ProductProductionColorDecision value = new ProductProductionColorDecision();
        value.setProductProductionColorDecisionId(id);
        value.setProductId(5L);
        value.setCodeItemId(id);
        value.setColorCode(code);
        value.setColorName(name);
        value.setDecisionBatchNo("B1");
        value.setSelectedFlag(1);
        value.setStatus("confirmed");
        value.setDeletedFlag(0);
        return value;
    }
    private record PhoneCreationFixture(ProductServiceImpl service,ProductRepository products,OperationLogService audit,com.yuewei.plm.module.moldreference.PhoneModelReferenceService phones) {}
    private PhoneCreationFixture phoneCreationFixture() {
        var products=mock(ProductRepository.class);var audit=mock(OperationLogService.class);var colors=mock(ProductProductionColorDecisionRepository.class);
        var phones=mock(com.yuewei.plm.module.moldreference.PhoneModelReferenceService.class);var access=mock(com.yuewei.plm.module.pilot.PilotAccessService.class);
        Product parent=new Product();parent.setProductId(5L);parent.setProductType("product_line");parent.setStatus("released");parent.setDeletedFlag(0);parent.setSeriesName("原平台");parent.setProductSpecificCode("FA");when(products.selectById(5L)).thenReturn(parent);when(colors.selectList(Mockito.<Wrapper<ProductProductionColorDecision>>any())).thenReturn(List.of(color(10L,"01","黑色")));
        Mockito.doAnswer(call->{Product p=call.getArgument(0);p.setProductId(20L);return 1;}).when(products).insert(Mockito.any(Product.class));
        var service=new ProductServiceImpl(products,mock(ProductCodeGenerator.class),audit,new JacksonConfig().objectMapper(),mock(ProductReleaseGateValidator.class),mock(BomInheritanceService.class),mock(ProcessRouteInheritanceService.class),colors,mock(ProductVariantColorRepository.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"phoneModelReferences",phones);org.springframework.test.util.ReflectionTestUtils.setField(service,"pilotAccessService",access);when(access.actor()).thenReturn(new CurrentUser(7L,"real-engineer","真实工程账号",false));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"productSalesPolicy",mock(com.yuewei.plm.module.productionmetadata.ProductSalesPolicy.class));
        return new PhoneCreationFixture(service,products,audit,phones);
    }
    private ProductCreateDTO selectedPhoneDto() {var dto=new ProductCreateDTO();dto.setParentProductId(5L);dto.setProductType("model_variant");dto.setProductName("原模具新增兼容型号");dto.setModel("Apple iPhone 18 Pro");dto.setPhoneModelId(72L);dto.setPhoneModelCode("1217");dto.setVersionNo("A");dto.setCreatedBy("client-spoofed-author");return dto;}
    @Test void selectedStandardPhonePersistsStableIdentityOriginalCodeAndRealActorAudit() {
        var f=phoneCreationFixture();when(f.phones().requireSelectedPhone(72L,null,"1217","Apple iPhone 18 Pro")).thenReturn(Map.of("codeItemId",317L));
        f.service().create(selectedPhoneDto());var inserted=ArgumentCaptor.forClass(Product.class);verify(f.products()).insert(inserted.capture());assertThat(inserted.getValue().getPhoneModelMemberId()).isEqualTo(72L);assertThat(inserted.getValue().getPhoneModelCode()).isEqualTo("1217");assertThat(inserted.getValue().getCreatedBy()).isEqualTo("real-engineer");
        var log=ArgumentCaptor.forClass(OperationLogCreateCommand.class);verify(f.audit()).logRequired(log.capture(),Mockito.eq(new CurrentUser(7L,"real-engineer","真实工程账号",false)));assertThat(log.getValue().getDetailJson()).contains("317","1217","72");
        Product saved=inserted.getValue();when(f.products().selectById(20L)).thenReturn(saved);
        var changed=new ProductUpdateDTO();changed.setUpdatedBy("editor");changed.setModel("Samsung Galaxy A37 5G");
        assertThatThrownBy(()->f.service().update(20L,changed)).isInstanceOf(BusinessException.class).hasMessageContaining("身份");
        assertThatThrownBy(()->f.service().updateBasicInfo(20L,changed)).isInstanceOf(BusinessException.class).hasMessageContaining("身份");
        changed.setModel(null);changed.setPhoneModelCode("1144");
        assertThatThrownBy(()->f.service().updateBasicInfo(20L,changed)).isInstanceOf(BusinessException.class).hasMessageContaining("换码");verify(f.products(),never()).updateById(Mockito.any(Product.class));
        changed.setPhoneModelCode(null);changed.setProductName("项目名称更新");saved.setStatus("released");saved.setLockStatus("frozen");
        f.service().updateBasicInfo(20L,changed);assertThat(saved.getModel()).isEqualTo("Apple iPhone 18 Pro");assertThat(saved.getPhoneModelMemberId()).isEqualTo(72L);assertThat(saved.getPhoneModelCode()).isEqualTo("1217");assertThat(saved.getStatus()).isEqualTo("released");verify(f.products()).updateById(saved);verify(f.phones()).requireSelectedPhone(72L,null,"1217","Apple iPhone 18 Pro");
    }
    @Test void selectedStandardPhoneRejectsEngravedCodeSubstitutionBeforeProjectInsert() {
        var f=phoneCreationFixture();var dto=selectedPhoneDto();dto.setPhoneModelCode("1144");when(f.phones().requireSelectedPhone(72L,null,"1144","Apple iPhone 18 Pro")).thenThrow(new BusinessException(40001,"不能用模具刻码替换"));
        assertThatThrownBy(()->f.service().create(dto)).isInstanceOf(BusinessException.class).hasMessageContaining("刻码");verify(f.products(),never()).insert(Mockito.any(Product.class));verify(f.audit(),never()).logRequired(Mockito.any(),Mockito.any());
    }
    @Test void manualModelProjectCannotBypassTheSharedOnSaleRule() {
        var f=phoneCreationFixture();var policy=mock(com.yuewei.plm.module.productionmetadata.ProductSalesPolicy.class);
        org.springframework.test.util.ReflectionTestUtils.setField(f.service(),"productSalesPolicy",policy);
        Mockito.doThrow(new BusinessException(40001,"骑士已停售")).when(policy).requireOnSale(List.of(5L));
        assertThatThrownBy(()->f.service().create(selectedPhoneDto())).hasMessageContaining("已停售");
        verify(f.products(),never()).insert(Mockito.any(Product.class));
    }

}
