package com.yuewei.plm.module.bom.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Data;

@Data
public class BomCopyVersionDTO {
    @NotBlank
    private String versionNo;
    private List<String> selectedColors;
}
