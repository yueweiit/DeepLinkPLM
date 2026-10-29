package com.yuewei.plm.module.bom.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class ProductBomItemDTO {

    private Long inventoryId;
    private String itemCode;

    @NotBlank(message = "物料名称不能为空")
    private String itemName;

    private String specification;
    private Long nodeId;
    private java.util.List<String> articleCodes;
    private String unitRaw;
    private String unitCn;
    private String unitEs;
    private String componentRowId;
    private String childCodeNormalized;

    @NotNull(message = "行号不能为空")
    private Integer lineNo;

    @NotNull(message = "用量不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "用量必须大于0")
    @jakarta.validation.constraints.Digits(integer=16,fraction=8,message="用量最多16位整数、8位小数，与 MES 明细精度一致")
    private BigDecimal quantity;

    @NotBlank(message = "单位不能为空")
    private String unit;

    private BigDecimal lossRate;

    @DecimalMin(value = "0", message = "单价不能为负数")
    private BigDecimal unitCost;

    @DecimalMin(value = "0", message = "单个成本不能为负数")
    private BigDecimal lineCost;

    private String supplierCode;
    private String supplierName;
    private String currencyCode;
    private String materialSource;
    private Integer unmatchedFlag;
    private String lookupMessage;
    private Integer substituteFlag;
    private String remark;
}
