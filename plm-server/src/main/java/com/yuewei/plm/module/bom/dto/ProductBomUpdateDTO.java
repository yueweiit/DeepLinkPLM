package com.yuewei.plm.module.bom.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ProductBomUpdateDTO {
    private String originalHash;
    @jakarta.validation.constraints.DecimalMin(value="0",inclusive=false)
    private java.math.BigDecimal outputQty;
    private String parentCode;
    private String parentName;
    private String customBomSpecification;

    @NotBlank(message = "BOM名称不能为空")
    private String bomName;

    @NotBlank(message = "BOM类型不能为空")
    private String bomType;

    @NotBlank(message = "版本号不能为空")
    private String versionNo;

    private String remark;
}
