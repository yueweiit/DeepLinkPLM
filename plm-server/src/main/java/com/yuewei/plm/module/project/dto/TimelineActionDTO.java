package com.yuewei.plm.module.project.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineActionDTO {

    private Long developmentVersion;
    private String remark;
    private String reason;
    private Boolean returnToPrevious;
    @jakarta.validation.constraints.Positive
    private Integer returnStepNo;
    @jakarta.validation.constraints.Positive
    private Long developmentRoundId;
    public TimelineActionDTO(String remark,String reason,Boolean previous){this.remark=remark;this.reason=reason;this.returnToPrevious=previous;}

}
