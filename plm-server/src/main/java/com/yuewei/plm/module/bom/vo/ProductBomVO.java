package com.yuewei.plm.module.bom.vo;

import com.yuewei.plm.module.bom.entity.ProductBom;
import com.yuewei.plm.module.bom.entity.ProductBomRoute;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProductBomVO {

    private Long productBomId;
    private Long productId;
    private String bomCode;
    private String bomName;
    private String bomType;
    private String bomScope;
    private String sourceType;
    @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
    private BigDecimal outputQty;
    private String parentCode;
    private String parentName;
    private String customBomSpecification;
    private String versionNo;
    private String status;
    private Long productBomRouteId;
    private Long processId;
    private String routeCode;
    private String routeName;
    private String candidateStatus;
    private Boolean currentFormal;
    private Integer materialCount;
    private BigDecimal totalCost;
    private Integer frozenFlag;
    private LocalDateTime frozenAt;
    private String frozenBy;
    private String remark;
    private List<ProductBomItemVO> items;

    public static ProductBomVO from(ProductBom bom, List<ProductBomItemVO> items) {
        return from(bom, items, null, false, null);
    }

    public static ProductBomVO from(ProductBom bom, List<ProductBomItemVO> items, ProductBomRoute route,
                                    boolean currentFormal, BigDecimal totalCost) {
        return ProductBomVO.builder()
            .productBomId(bom.getProductBomId())
            .productId(bom.getProductId())
            .bomCode(bom.getBomCode())
            .bomName(bom.getBomName())
            .bomType(bom.getBomType())
            .bomScope(bom.getBomScope())
            .sourceType(bom.getSourceType())
            .outputQty(bom.getQuantity())
            .parentCode(bom.getOutputInventoryCode())
            .parentName(bom.getOutputInventoryName())
            .customBomSpecification(bom.getCustomBomSpecification())
            .versionNo(bom.getVersionNo())
            .status(bom.getStatus())
            .productBomRouteId(route == null ? null : route.getProductBomRouteId())
            .processId(route == null ? null : route.getProcessId())
            .routeCode(route == null ? null : route.getRouteCode())
            .routeName(route == null ? null : route.getRouteName())
            .candidateStatus(bom.getStatus())
            .currentFormal(currentFormal)
            .materialCount(items == null ? 0 : items.size())
            .totalCost(totalCost)
            .frozenFlag(bom.getFrozenFlag())
            .frozenAt(bom.getFrozenAt())
            .frozenBy(bom.getFrozenBy())
            .remark(bom.getRemark())
            .items(items)
            .build();
    }
}
