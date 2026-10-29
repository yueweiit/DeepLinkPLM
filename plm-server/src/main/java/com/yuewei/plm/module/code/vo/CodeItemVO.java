package com.yuewei.plm.module.code.vo;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CodeItemVO {
    private Long codeItemId;
    private String codeType;
    private String codeValue;
    private String codeName;
    private String codeNameZh;
    private String status;
    private String productionStatus;
    private java.util.List<java.util.Map<String,Object>> brands;
    private Integer sortOrder;
    private Long version;
    private LocalDateTime updatedAt;
}
