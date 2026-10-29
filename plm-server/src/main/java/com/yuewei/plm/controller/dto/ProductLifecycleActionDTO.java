package com.yuewei.plm.controller.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProductLifecycleActionDTO {

    @jakarta.validation.constraints.PositiveOrZero
    private Long developmentVersion;
    @Size(max = 1000, message = "原因不能超过1000个字符")
    private String reason;

    private Boolean riskConfirmed;

    @Size(max = 4000)
    private String exceptionReason;
    private java.time.LocalDate supplementDueDate;
    @jakarta.validation.constraints.Positive
    private Long developmentResponsibleUserId;
    @jakarta.validation.Valid
    @Size(max = 100)
    private java.util.List<com.yuewei.plm.module.project.development.DevelopmentModels.MaterialReference> developmentReferences;
}
