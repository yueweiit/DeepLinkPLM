package com.yuewei.plm.module.bom.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.yuewei.plm.common.constant.ErrorCodeConstants;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.security.CurrentUserContext;
import com.yuewei.plm.module.pilotdelivery.BomReleaseDeliveryService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import com.yuewei.plm.module.bom.dto.ProductBomCreateDTO;
import com.yuewei.plm.module.bom.dto.ProductBomItemDTO;
import com.yuewei.plm.module.bom.dto.ProductBomUpdateDTO;
import com.yuewei.plm.module.bom.entity.ProductBom;
import com.yuewei.plm.module.bom.entity.ProductBomCostSnapshot;
import com.yuewei.plm.module.bom.entity.ProductBomItem;
import com.yuewei.plm.module.bom.entity.ProductBomRoute;
import com.yuewei.plm.module.bom.entity.ProductBomRouteColor;
import com.yuewei.plm.module.bom.entity.ProductBomRouteFormalSelection;
import com.yuewei.plm.module.bom.repository.ProductBomCostSnapshotRepository;
import com.yuewei.plm.module.bom.repository.ProductBomItemRepository;
import com.yuewei.plm.module.bom.repository.ProductBomRepository;
import com.yuewei.plm.module.bom.repository.ProductBomRouteColorRepository;
import com.yuewei.plm.module.bom.repository.ProductBomRouteFormalSelectionRepository;
import com.yuewei.plm.module.bom.repository.ProductBomRouteRepository;
import com.yuewei.plm.module.bom.service.ProductBomService;
import com.yuewei.plm.module.bom.vo.ProductBomItemVO;
import com.yuewei.plm.module.bom.vo.ProductBomVO;
import com.yuewei.plm.module.operationlog.constant.OperationActionConstants;
import com.yuewei.plm.module.operationlog.service.OperationLogCreateCommand;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.process.entity.ProcessEntity;
import com.yuewei.plm.module.process.repository.ProcessRepository;
import com.yuewei.plm.repository.ProductRepository;
import com.yuewei.plm.repository.entity.Product;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ProductBomServiceImpl implements ProductBomService {

    private static final String STATUS_DRAFT = "draft";
    private static final String STATUS_FROZEN = "frozen";
    private static final String STATUS_RELEASED = "released";
    private static final String STATUS_ARCHIVED = "archived";
    private static final String BOM_SCOPE_CANDIDATE = "candidate";
    private static final String BOM_SCOPE_FORMAL = "formal";
    private static final String PROCESS_TYPE_ROUTING = "routing";

    private final ProductRepository productRepository;
    private final ProductBomRepository productBomRepository;
    private final ProductBomItemRepository productBomItemRepository;
    private final ProductBomRouteRepository productBomRouteRepository;
    private final ProductBomRouteColorRepository productBomRouteColorRepository;
    private final ProductBomCostSnapshotRepository productBomCostSnapshotRepository;
    private final ProductBomRouteFormalSelectionRepository productBomRouteFormalSelectionRepository;
    private final ProcessRepository processRepository;
    private final OperationLogService operationLogService;
    private final BomReleaseDeliveryService releaseDelivery;
    private final PilotAccessService access;
    private final JdbcTemplate jdbc;
    private final com.yuewei.plm.module.partner.SupplierProfileService suppliers;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.yuewei.plm.module.bom.service.BomStructureService structure;

    @Override
    public List<ProductBomVO> listByProject(Long projectId) {
        getProductOrThrow(projectId);
        Set<Long> currentFormalBomIds = currentFormalBomIds(projectId);
        return productBomRepository.selectList(new LambdaQueryWrapper<ProductBom>()
                .eq(ProductBom::getProductId, projectId)
                .eq(ProductBom::getDeletedFlag, 0)
                .orderByDesc(ProductBom::getProductBomId))
            .stream()
            .map(bom -> ProductBomVO.from(
                bom,
                listItems(bom.getProductBomId()),
                firstActiveRoute(bom.getProductBomId()),
                currentFormalBomIds.contains(bom.getProductBomId()),
                null
            ))
            .toList();
    }

    @Override
    public ProductBomVO getById(Long bomId) {
        ProductBom bom = getBomOrThrow(bomId);
        return ProductBomVO.from(
            bom,
            listItems(bom.getProductBomId()),
            firstActiveRoute(bom.getProductBomId()),
            currentFormalBomIds(bom.getProductId()).contains(bom.getProductBomId()),
            null
        );
    }

    @Override
    @Transactional
    public ProductBomVO create(Long projectId, ProductBomCreateDTO dto, HttpServletRequest request) {
        Product product = getProductOrThrow(projectId);
        ProcessEntity processRoute = requireProjectRoute(projectId, dto.getProcessId());
        Long graphNodes=jdbc.queryForObject("select count(*) from plm_process where parent_process_id=? and node_code is not null and deleted_flag=0",Long.class,dto.getProcessId());
        boolean graph=graphNodes!=null&&graphNodes>0;
        validateOutput(dto.getOutputQty());
        LocalDateTime now = LocalDateTime.now();
        String operator = currentUserName();
        ProductBom bom = new ProductBom();
        bom.setQuantity(dto.getOutputQty());bom.setOutputInventoryCode(dto.getParentCode());bom.setOutputInventoryName(dto.getParentName());bom.setCustomBomSpecification(dto.getCustomBomSpecification());
        bom.setProductId(projectId);
        bom.setBomCode("BOM-" + projectId + "-" + System.currentTimeMillis());
        bom.setBomName(dto.getBomName());
        bom.setBomType(dto.getBomType());
        bom.setBomScope(BOM_SCOPE_CANDIDATE);
        bom.setSourceType(graph?"engineering_graph":"manual");
        bom.setVersionNo(dto.getVersionNo());
        bom.setStatus(STATUS_DRAFT);
        bom.setCurrencyCode(graph?null:"CNY");
        bom.setFrozenFlag(0);
        bom.setRemark(dto.getRemark());
        fillCreateAudit(bom, now, operator);
        productBomRepository.insert(bom);
        if(graph){access.requireEngineeringMaintenance();jdbc.update("update plm_product_bom set quantity=?,currency_code=null where product_bom_id=?",dto.getOutputQty(),bom.getProductBomId());}
        ProductBomRoute route = new ProductBomRoute();
        route.setProductBomId(bom.getProductBomId());
        route.setProductId(projectId);
        route.setProcessId(processRoute.getProcessId());
        route.setRouteCode(processRoute.getProcessCode());
        route.setRouteName(processRoute.getProcessName());
        route.setSharedBomGroupCode("BOM-" + bom.getProductBomId());
        route.setRouteVariantNo("BASE");
        route.setVariantName("基础用料");
        route.setVariantSourceType("manual");
        route.setStatus("active");
        fillCreateAudit(route, now, operator);
        productBomRouteRepository.insert(route);
        writeLog(OperationActionConstants.BOM_CREATE, bom, product, "{\"action\":\"create\"}", request);
        return getById(bom.getProductBomId());
    }

    @Override
    @Transactional
    public ProductBomVO update(Long bomId, ProductBomUpdateDTO dto, HttpServletRequest request) {
        ProductBom bom = requireEditableBom(bomId);
        if(structure!=null&&com.yuewei.plm.module.bom.service.BomStructureService.structured(bom)&&!structure.fingerprint(bomId).equals(dto.getOriginalHash()))throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR,"BOM 主档或明细已变化，请重新载入后维护");
        validateOutput(dto.getOutputQty());
        if(dto.getOutputQty()!=null)bom.setQuantity(dto.getOutputQty());
        if(dto.getParentCode()!=null)bom.setOutputInventoryCode(dto.getParentCode());if(dto.getParentName()!=null)bom.setOutputInventoryName(dto.getParentName());if(dto.getCustomBomSpecification()!=null)bom.setCustomBomSpecification(dto.getCustomBomSpecification());
        Product product = getProductOrThrow(bom.getProductId());
        bom.setBomName(dto.getBomName());
        bom.setBomType(dto.getBomType());
        bom.setVersionNo(dto.getVersionNo());
        bom.setRemark(dto.getRemark());
        fillUpdateAudit(bom);
        productBomRepository.updateById(bom);
        writeLog(OperationActionConstants.BOM_UPDATE, bom, product, "{\"action\":\"update\"}", request);
        return getById(bomId);
    }

    @Override
    @Transactional
    public ProductBomVO addItem(Long bomId, ProductBomItemDTO dto, HttpServletRequest request) {
        ProductBom bom = requireEditableBom(bomId);
        requireLegacyItemWriter(bom);
        Product product = getProductOrThrow(bom.getProductId());
        requireValidItem(dto);
        requireUniqueLineNo(bomId, dto.getLineNo(), null);
        LocalDateTime now = LocalDateTime.now();
        String operator = currentUserName();
        ProductBomItem item = new ProductBomItem();
        applyItem(dto, item, bom);
        item.setProductBomId(bomId);
        item.setProductId(bom.getProductId());
        item.setVersionNo(bom.getVersionNo());
        item.setStatus(STATUS_DRAFT);
        fillCreateAudit(item, now, operator);
        productBomItemRepository.insert(item);
        writeLog(OperationActionConstants.BOM_ITEM_CREATE, bom, product,
            "{\"action\":\"item_create\",\"itemId\":" + item.getProductBomItemId() + "}", request);
        return getById(bomId);
    }

    @Override
    @Transactional
    public ProductBomVO updateItem(Long bomId, Long itemId, ProductBomItemDTO dto, HttpServletRequest request) {
        ProductBom bom = requireEditableBom(bomId);
        requireLegacyItemWriter(bom);
        Product product = getProductOrThrow(bom.getProductId());
        requireValidItem(dto);
        ProductBomItem item = getItemOrThrow(bomId, itemId);
        requireUniqueLineNo(bomId, dto.getLineNo(), itemId);
        applyItem(dto, item, bom);
        fillUpdateAudit(item);
        productBomItemRepository.updateById(item);
        writeLog(OperationActionConstants.BOM_ITEM_UPDATE, bom, product,
            "{\"action\":\"item_update\",\"itemId\":" + itemId + "}", request);
        return getById(bomId);
    }

    @Override
    @Transactional
    public ProductBomVO deleteItem(Long bomId, Long itemId, HttpServletRequest request) {
        ProductBom bom = requireEditableBom(bomId);
        requireLegacyItemWriter(bom);
        Product product = getProductOrThrow(bom.getProductId());
        ProductBomItem item = getItemOrThrow(bomId, itemId);
        item.setDeletedFlag(1);
        fillUpdateAudit(item);
        productBomItemRepository.updateById(item);
        writeLog(OperationActionConstants.BOM_ITEM_DELETE, bom, product,
            "{\"action\":\"item_delete\",\"itemId\":" + itemId + "}", request);
        return getById(bomId);
    }

    @Override
    @Transactional
    public void deleteVersion(Long bomId, HttpServletRequest request) {
        ProductBom bom = requireDeletableBom(bomId);
        Product product = getProductOrThrow(bom.getProductId());
        LocalDateTime now = LocalDateTime.now();
        String operator = currentUserName();

        productBomRepository.update(null, new UpdateWrapper<ProductBom>()
            .set("deleted_flag", 1)
            .set("updated_at", now)
            .set("updated_by", operator)
            .eq("product_bom_id", bomId)
            .eq("deleted_flag", 0));
        productBomRouteRepository.update(null, new UpdateWrapper<ProductBomRoute>()
            .set("deleted_flag", 1)
            .set("updated_at", now)
            .set("updated_by", operator)
            .eq("product_bom_id", bomId)
            .eq("deleted_flag", 0));
        productBomRouteColorRepository.update(null, new UpdateWrapper<ProductBomRouteColor>()
            .set("deleted_flag", 1)
            .set("updated_at", now)
            .set("updated_by", operator)
            .eq("product_bom_id", bomId)
            .eq("deleted_flag", 0));
        productBomItemRepository.update(null, new UpdateWrapper<ProductBomItem>()
            .set("deleted_flag", 1)
            .set("updated_at", now)
            .set("updated_by", operator)
            .eq("product_bom_id", bomId)
            .eq("deleted_flag", 0));
        productBomCostSnapshotRepository.update(null, new UpdateWrapper<ProductBomCostSnapshot>()
            .set("deleted_flag", 1)
            .set("updated_at", now)
            .set("updated_by", operator)
            .eq("product_bom_id", bomId)
            .eq("deleted_flag", 0));
        productBomRouteFormalSelectionRepository.update(null, new UpdateWrapper<ProductBomRouteFormalSelection>()
            .set("deleted_flag", 1)
            .set("updated_at", now)
            .set("updated_by", operator)
            .eq("product_bom_id", bomId)
            .eq("deleted_flag", 0));

        writeLog(OperationActionConstants.BOM_DELETE, bom, product,
            "{\"action\":\"delete_version\",\"versionNo\":\"" + bom.getVersionNo() + "\"}", request);
    }

    @Override
    @Transactional
    public ProductBomVO freeze(Long bomId, HttpServletRequest request) {
        ProductBom bom = requireEditableBom(bomId);
        Product product = getProductOrThrow(bom.getProductId());
        if (listItems(bomId).isEmpty()) {
            throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, "BOM至少需要1条明细才能冻结");
        }
        bom.setStatus(STATUS_FROZEN);
        bom.setFrozenAt(LocalDateTime.now());
        bom.setFrozenBy(currentUserName());
        fillUpdateAudit(bom);
        productBomRepository.updateById(bom);
        writeLog(OperationActionConstants.BOM_FREEZE, bom, product, "{\"action\":\"freeze\"}", request);
        return getById(bomId);
    }

    @Override
    @Transactional
    public ProductBomVO confirmCurrentVersion(Long bomId, HttpServletRequest request) {
        lock(bomId);
        ProductBom bom = getBomOrThrow(bomId);
        access.requirePermission("bom:publish");access.requireProduct(bom.getProductId());
        access.requireEngineeringMaintenance();
        if(STATUS_RELEASED.equals(bom.getStatus())){releaseDelivery.released(bom);return getById(bomId);}
        if(!Set.of("reviewing",STATUS_FROZEN).contains(bom.getStatus())||"test".equals(bom.getBomType()))throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR,"请先提交审核；只有审核中或冻结的正式候选版本可确认发布");
        Product product = getProductOrThrow(bom.getProductId());
        List<ProductBomRoute> routes = requireConfirmableRoutes(bom);
        LocalDateTime now = LocalDateTime.now();
        String operator = currentUserName();
        String batchNo = UUID.randomUUID().toString();
        if(com.yuewei.plm.module.bom.service.BomStructureService.structured(bom)){
            long family=bom.getSourceProductBomId()==null?bomId:bom.getSourceProductBomId();
            jdbc.update("update plm_product_bom_route_formal_selection s set status='invalidated',invalidated_at=?,invalidated_reason='工程确认同来源配方的新版本',updated_at=?,updated_by=? from plm_product_bom old where old.product_bom_id=s.product_bom_id and s.status='active' and s.deleted_flag=0 and old.product_id=? and (old.product_bom_id=? or old.source_product_bom_id=?)",now,now,operator,bom.getProductId(),family,family);
        }
        for (ProductBomRoute route : routes) {
            if(com.yuewei.plm.module.bom.service.BomStructureService.structured(bom))jdbc.update("update plm_process set status='locked',frozen_at=?,frozen_by=?,updated_at=?,updated_by=? where process_id=? and status in ('draft','confirmed')",now,operator,now,operator,route.getProcessId());
            invalidateCurrentRouteSelection(bom.getProductId(), route.getProductBomRouteId(), now, operator,
                "重新确认当前 BOM 版本");
            ProductBomRouteFormalSelection selection = new ProductBomRouteFormalSelection();
            selection.setProductId(bom.getProductId());
            selection.setProductBomId(bom.getProductBomId());
            selection.setProductBomRouteId(route.getProductBomRouteId());
            selection.setProcessId(route.getProcessId());
            selection.setBomVersionNo(bom.getVersionNo());
            selection.setSelectionBatchNo(batchNo);
            selection.setStatus("active");
            selection.setConfirmedAt(now);
            selection.setConfirmedBy(operator);
            selection.setRemark("confirm current BOM version");
            fillCreateAudit(selection, now, operator);
            productBomRouteFormalSelectionRepository.insert(selection);
        }
        bom.setBomScope(BOM_SCOPE_FORMAL);
        bom.setStatus(STATUS_RELEASED);
        bom.setConfirmedAt(now);
        bom.setConfirmedBy(operator);
        bom.setFrozenFlag(0);
        bom.setFrozenAt(null);
        bom.setFrozenBy(null);
        bom.setReleasedAt(now);
        bom.setReleasedBy(operator);
        fillUpdateAudit(bom);
        productBomRepository.updateById(bom);
        releaseDelivery.released(bom);
        writeLog(OperationActionConstants.BOM_CONFIRM_CURRENT, bom, product,
            "{\"action\":\"confirm_current_version\",\"versionNo\":\"" + bom.getVersionNo() + "\"}", request);
        return getById(bomId);
    }

    @Override
    @Transactional
    public ProductBomVO cancelCurrentConfirmation(Long bomId, HttpServletRequest request) {
        lock(bomId);
        ProductBom bom = getBomOrThrow(bomId);
        access.requirePermission("bom:publish");access.requireProduct(bom.getProductId());
        Product product = getProductOrThrow(bom.getProductId());
        LocalDateTime now = LocalDateTime.now();
        String operator = currentUserName();
        List<ProductBomRouteFormalSelection> activeSelections = activeSelectionsByBom(bom.getProductId(), bomId);
        if (activeSelections.isEmpty()) {
            throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, "当前 BOM 版本尚未确认");
        }
        for (ProductBomRouteFormalSelection selection : activeSelections) {
            selection.setStatus("invalidated");
            selection.setInvalidatedAt(now);
            selection.setInvalidatedReason("取消确认当前 BOM 版本");
            selection.setUpdatedAt(now);
            selection.setUpdatedBy(operator);
            productBomRouteFormalSelectionRepository.updateById(selection);
        }
        // Withdraw the current selection only. A released version and its ERP snapshot stay immutable.
        writeLog(OperationActionConstants.BOM_CANCEL_CONFIRM, bom, product,
            "{\"action\":\"cancel_current_confirmation\",\"versionNo\":\"" + bom.getVersionNo() + "\"}", request);
        return getById(bomId);
    }

    private Product getProductOrThrow(Long projectId) {
        Product product = productRepository.selectById(projectId);
        if (product == null || Integer.valueOf(1).equals(product.getDeletedFlag())) {
            throw new BusinessException(ErrorCodeConstants.RESOURCE_NOT_FOUND, "项目不存在");
        }
        return product;
    }

    private ProductBom getBomOrThrow(Long bomId) {
        ProductBom bom = productBomRepository.selectById(bomId);
        if (bom == null || Integer.valueOf(1).equals(bom.getDeletedFlag())) {
            throw new BusinessException(ErrorCodeConstants.RESOURCE_NOT_FOUND, "BOM不存在");
        }
        return bom;
    }

    private ProductBom requireEditableBom(Long bomId) {
        lock(bomId);
        ProductBom bom = getBomOrThrow(bomId);
        if(com.yuewei.plm.module.bom.service.BomStructureService.structured(bom)){access.requireEngineeringMaintenance();access.requireProduct(bom.getProductId());}
        if (Set.of(STATUS_ARCHIVED,STATUS_RELEASED,STATUS_FROZEN).contains(bom.getStatus())||Integer.valueOf(1).equals(bom.getFrozenFlag())) {
            throw new BusinessException(ErrorCodeConstants.VERSION_FROZEN, "已发布、冻结或归档BOM不能修改，请复制新版本");
        }
        return bom;
    }

    private void requireLegacyItemWriter(ProductBom bom) {
        if(com.yuewei.plm.module.bom.service.BomStructureService.structured(bom))throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR,"此 BOM 包含明确投料节点和范围，请使用工程用料／路线编辑器整体保存，旧明细接口不能绕过原值和节点校验");
    }

    private ProductBom requireDeletableBom(Long bomId) {
        lock(bomId);
        ProductBom bom = getBomOrThrow(bomId);
        if (Set.of(STATUS_ARCHIVED,STATUS_RELEASED,STATUS_FROZEN).contains(bom.getStatus())) {
            throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, "已发布、冻结或归档BOM不能删除");
        }
        if (currentFormalBomIds(bom.getProductId()).contains(bomId)) {
            throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, "BOM已被正式选用，不能删除");
        }
        return bom;
    }

    private void lock(Long bomId){jdbc.queryForList("select product_bom_id from plm_product_bom where product_bom_id=? for update",bomId);}

    private ProductBomItem getItemOrThrow(Long bomId, Long itemId) {
        ProductBomItem item = productBomItemRepository.selectById(itemId);
        if (item == null || Integer.valueOf(1).equals(item.getDeletedFlag()) || !bomId.equals(item.getProductBomId())) {
            throw new BusinessException(ErrorCodeConstants.RESOURCE_NOT_FOUND, "BOM明细不存在");
        }
        return item;
    }

    private List<ProductBomItemVO> listItems(Long bomId) {
        return productBomItemRepository.selectList(new LambdaQueryWrapper<ProductBomItem>()
                .eq(ProductBomItem::getProductBomId, bomId)
                .eq(ProductBomItem::getDeletedFlag, 0)
                .orderByAsc(ProductBomItem::getLineNo))
            .stream()
            .map(ProductBomItemVO::from)
            .toList();
    }

    private ProductBomRoute firstActiveRoute(Long bomId) {
        List<ProductBomRoute> routes = productBomRouteRepository.selectList(new LambdaQueryWrapper<ProductBomRoute>()
            .eq(ProductBomRoute::getProductBomId, bomId)
            .eq(ProductBomRoute::getStatus, "active")
            .eq(ProductBomRoute::getDeletedFlag, 0)
            .orderByAsc(ProductBomRoute::getProductBomRouteId));
        return routes == null || routes.isEmpty() ? null : routes.get(0);
    }

    private List<ProductBomRoute> activeRoutes(Long bomId) {
        List<ProductBomRoute> routes = productBomRouteRepository.selectList(new LambdaQueryWrapper<ProductBomRoute>()
            .eq(ProductBomRoute::getProductBomId, bomId)
            .eq(ProductBomRoute::getStatus, "active")
            .eq(ProductBomRoute::getDeletedFlag, 0)
            .orderByAsc(ProductBomRoute::getProductBomRouteId));
        return routes == null ? List.of() : routes;
    }

    private List<ProductBomRoute> requireConfirmableRoutes(ProductBom bom) {
        List<ProductBomRoute> routes = activeRoutes(bom.getProductBomId());
        if (routes.isEmpty()) {
            throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, "BOM 至少需要一条有效工艺路线才能确认");
        }
        for (ProductBomRoute route : routes) {
            if(com.yuewei.plm.module.bom.service.BomStructureService.structured(bom)){
                if(bom.getQuantity()==null||bom.getQuantity().signum()<=0||!StringUtils.hasText(bom.getOutputInventoryCode())||!StringUtils.hasText(bom.getOutputInventoryName()))throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR,"请先完善基准产量、主件编码及名称，不能猜测默认值");
                Long invalid=jdbc.queryForObject("select count(*) from plm_product_bom_item i left join plm_bom_item_target t on t.product_bom_item_id=i.product_bom_item_id and t.product_bom_route_id=i.product_bom_route_id where i.product_bom_route_id=? and i.deleted_flag=0 and (i.quantity is null or i.quantity<=0 or nullif(btrim(i.uom_code),'') is null or t.process_id is null)",Long.class,route.getProductBomRouteId());
                Long applicable=jdbc.queryForObject("select count(*) from plm_bom_item_target t join plm_product_bom_item i on i.product_bom_item_id=t.product_bom_item_id where t.product_bom_route_id=? and i.deleted_flag=0",Long.class,route.getProductBomRouteId());
                if(invalid==null||invalid!=0||applicable==null||applicable==0)throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR,"MES 来源路线投料资料不完整，不能确认");
                continue;
            }
            Long colorCount = productBomRouteColorRepository.selectCount(new LambdaQueryWrapper<ProductBomRouteColor>()
                .eq(ProductBomRouteColor::getProductBomRouteId, route.getProductBomRouteId())
                .eq(ProductBomRouteColor::getStatus, "active")
                .eq(ProductBomRouteColor::getDeletedFlag, 0));
            if (colorCount == null || colorCount == 0) {
                throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, "BOM 路线缺少适用颜色，不能确认");
            }
            Long itemCount = productBomItemRepository.selectCount(new LambdaQueryWrapper<ProductBomItem>()
                .eq(ProductBomItem::getProductBomRouteId, route.getProductBomRouteId())
                .eq(ProductBomItem::getDeletedFlag, 0));
            if (itemCount == null || itemCount == 0) {
                throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, "BOM 路线缺少明细，不能确认");
            }
        }
        return routes;
    }

    private void invalidateCurrentRouteSelection(Long productId, Long productBomRouteId, LocalDateTime now,
                                                 String operator, String reason) {
        List<ProductBomRouteFormalSelection> selections = productBomRouteFormalSelectionRepository.selectList(
            new LambdaQueryWrapper<ProductBomRouteFormalSelection>()
                .eq(ProductBomRouteFormalSelection::getProductId, productId)
                .eq(ProductBomRouteFormalSelection::getProductBomRouteId, productBomRouteId)
                .eq(ProductBomRouteFormalSelection::getStatus, "active")
                .eq(ProductBomRouteFormalSelection::getDeletedFlag, 0));
        if (selections == null) return;
        for (ProductBomRouteFormalSelection selection : selections) {
            selection.setStatus("invalidated");
            selection.setInvalidatedAt(now);
            selection.setInvalidatedReason(reason);
            selection.setUpdatedAt(now);
            selection.setUpdatedBy(operator);
            productBomRouteFormalSelectionRepository.updateById(selection);
        }
    }

    private List<ProductBomRouteFormalSelection> activeSelectionsByBom(Long productId, Long bomId) {
        List<ProductBomRouteFormalSelection> selections = productBomRouteFormalSelectionRepository.selectList(
            new LambdaQueryWrapper<ProductBomRouteFormalSelection>()
                .eq(ProductBomRouteFormalSelection::getProductId, productId)
                .eq(ProductBomRouteFormalSelection::getProductBomId, bomId)
                .eq(ProductBomRouteFormalSelection::getStatus, "active")
                .eq(ProductBomRouteFormalSelection::getDeletedFlag, 0));
        return selections == null ? List.of() : selections;
    }

    private Set<Long> currentFormalBomIds(Long productId) {
        List<ProductBomRouteFormalSelection> selections = productBomRouteFormalSelectionRepository.selectList(
            new LambdaQueryWrapper<ProductBomRouteFormalSelection>()
                .eq(ProductBomRouteFormalSelection::getProductId, productId)
                .eq(ProductBomRouteFormalSelection::getStatus, "active")
                .eq(ProductBomRouteFormalSelection::getDeletedFlag, 0));
        return selections == null ? Set.of() : selections.stream()
            .map(ProductBomRouteFormalSelection::getProductBomId)
            .collect(java.util.stream.Collectors.toSet());
    }

    private ProcessEntity requireProjectRoute(Long projectId, Long processId) {
        ProcessEntity route = processRepository.selectById(processId);
        if (route == null || Integer.valueOf(1).equals(route.getDeletedFlag())) {
            throw new BusinessException(ErrorCodeConstants.RESOURCE_NOT_FOUND, "工艺路线不存在");
        }
        if (!projectId.equals(route.getProductId()) || !PROCESS_TYPE_ROUTING.equals(route.getProcessType())) {
            throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, "工艺路线不属于当前项目");
        }
        if ("archived".equals(route.getStatus())) {
            throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, "工艺路线已归档，不能关联 BOM");
        }
        return route;
    }

    private void requireUniqueLineNo(Long bomId, Integer lineNo, Long currentItemId) {
        Long count = productBomItemRepository.selectCount(new LambdaQueryWrapper<ProductBomItem>()
            .eq(ProductBomItem::getProductBomId, bomId)
            .eq(ProductBomItem::getLineNo, lineNo)
            .eq(ProductBomItem::getDeletedFlag, 0)
            .ne(currentItemId != null, ProductBomItem::getProductBomItemId, currentItemId));
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCodeConstants.CODE_CONFLICT, "同一BOM下行号不能重复");
        }
    }

    private void requireValidItem(ProductBomItemDTO dto) {
        if(dto.getQuantity()!=null&&(dto.getQuantity().scale()>8||dto.getQuantity().precision()-dto.getQuantity().scale()>16))throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR,"明细用量须符合 MES numeric(24,8) 精度");
        if (dto.getQuantity() == null || dto.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, "BOM明细用量必须大于0");
        }
        if (dto.getLineNo() == null || dto.getLineNo() <= 0) {
            throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, "BOM明细行号必须大于0");
        }
        if (!StringUtils.hasText(dto.getItemName()) || !StringUtils.hasText(dto.getUnit())) {
            throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, "BOM明细名称和单位不能为空");
        }
    }

    private void applyItem(ProductBomItemDTO dto, ProductBomItem item, ProductBom bom) {
        suppliers.requireSnapshotReference(dto.getSupplierCode(),item.getProductBomItemId()==null?null:item.getSupplierCodeSnapshot());
        com.yuewei.plm.module.masterdata.MasterDataAdoption.requireInventory(jdbc,dto.getInventoryId(),dto.getItemCode(),item.getProductBomItemId()==null?null:item.getInventoryId(),item.getProductBomItemId()==null?null:item.getItemCode());
        com.yuewei.plm.module.bom.service.BomAmountPolicy.apply(dto,item,
            item.getProductBomItemId()==null?null:item,access.hasPermission("finance:write"),bom.getCurrencyCode());
        item.setInventoryId(dto.getInventoryId());
        item.setItemCode(dto.getItemCode());
        item.setItemName(dto.getItemName());
        item.setSpecification(dto.getSpecification());
        item.setLineNo(dto.getLineNo());
        item.setQuantity(dto.getQuantity());
        item.setUnit(dto.getUnit());
        item.setLossRate(dto.getLossRate() == null ? BigDecimal.ZERO : dto.getLossRate());
        item.setSupplierCodeSnapshot(dto.getSupplierCode());
        item.setSupplierNameSnapshot(dto.getSupplierName());
        item.setMaterialSource(normalizeMaterialSource(dto));
        item.setUnmatchedFlag(normalizeUnmatchedFlag(dto, item.getMaterialSource()));
        item.setSubstituteFlag(dto.getSubstituteFlag() == null ? 0 : dto.getSubstituteFlag());
        item.setRemark(dto.getRemark());
    }

    private String normalizeMaterialSource(ProductBomItemDTO dto) {
        if (StringUtils.hasText(dto.getMaterialSource())) {
            return dto.getMaterialSource().trim();
        }
        return Integer.valueOf(1).equals(dto.getUnmatchedFlag()) ? "manual" : "inventory";
    }

    private Integer normalizeUnmatchedFlag(ProductBomItemDTO dto, String materialSource) {
        if (dto.getUnmatchedFlag() != null) {
            return dto.getUnmatchedFlag();
        }
        return "manual".equals(materialSource) ? 1 : 0;
    }

    private void fillCreateAudit(com.yuewei.plm.repository.entity.BaseEntity entity, LocalDateTime now, String operator) {
        entity.setCreatedAt(now);
        entity.setCreatedBy(operator);
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(operator);
        entity.setDeletedFlag(0);
    }

    private void fillUpdateAudit(com.yuewei.plm.repository.entity.BaseEntity entity) {
        entity.setUpdatedAt(LocalDateTime.now());
        entity.setUpdatedBy(currentUserName());
    }

    private String currentUserName() {
        return CurrentUserContext.get()
            .map(CurrentUser::displayName)
            .filter(StringUtils::hasText)
            .orElse("system");
    }

    private void validateOutput(BigDecimal value){if(value!=null&&(value.signum()<=0||value.stripTrailingZeros().scale()>4||value.precision()-value.scale()>14))throw new BusinessException(ErrorCodeConstants.VALIDATION_ERROR,"基准产量须符合 MES numeric(18,4) 精度且大于零");}

    private void writeLog(String action, ProductBom bom, Product product, String detailJson, HttpServletRequest request) {
        operationLogService.logRequired(OperationLogCreateCommand.builder()
            .action(action)
            .businessType("PRODUCT_BOM")
            .businessId(String.valueOf(bom.getProductBomId()))
            .businessCode(bom.getBomCode())
            .businessName(bom.getBomName())
            .detailJson(detailJson)
            .request(request)
            .build(),access.actor());
    }
}
