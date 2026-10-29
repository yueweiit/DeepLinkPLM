package com.yuewei.plm.module.project.development;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class DevelopmentModels {
    private DevelopmentModels() {}

    public record MaterialReference(
        @NotBlank @Pattern(regexp = "file|bom|process|link") String type,
        @NotNull @Positive Long id
    ) {}

    public record ProfileInput(
        @NotBlank @Size(max = 255) String productName,
        @Size(max = 128) String model,
        @Size(max = 10000) String requirements,
        @DecimalMin("0") @Digits(integer = 10, fraction = 4) BigDecimal targetCost,
        LocalDate expectedDeliveryDate,
        List<Long> phoneModelIds, String pendingReason, String currency, String costUnit, String notes,
        Long ownerUserId, List<MaterialReference> references, Long version, String changeReason
    ) {
        public ProfileInput(String name,String model,String requirements,BigDecimal cost,LocalDate date) {
            this(name,model,requirements,cost,date,null,null,null,null,null,null,null,null,null);
        }
    }

    public record RoundInput(
        @NotBlank @Pattern(regexp = "sample|test|pilot") String kind,
        LocalDate recordDate,
        @Size(max = 255) String title,
        @NotBlank @Pattern(regexp = "pending|passed|failed") String result,
        @Size(max = 10000) String issues,
        @Size(max = 10000) String changes,
        @Size(max = 10000) String retestResult,
        @Valid @Size(max = 100) List<@NotNull MaterialReference> references,
        boolean confirm, Map<String,Object> details, Long version, String requestId
    ) {
        public RoundInput(String kind,LocalDate date,String title,String result,String issues,String changes,String retest,List<MaterialReference> refs,boolean confirm) {
            this(kind,date,title,result,issues,changes,retest,refs,confirm,Map.of(),null,null);
        }
    }

    public record WorkflowInput(@NotBlank String action, @NotNull Long version,
        @Size(max=80) String requestId, @NotNull Map<String,Object> data) {}

    public record DesignDraftInput(@Valid @NotNull ProfileInput profile,@NotNull Map<String,Object> plan) {}
    public record StageReadinessInput(int stage,Long sampleRoundId,Map<String,Object> releaseDraft,Map<String,Object> draft) {
        public StageReadinessInput(int stage,Long sampleRoundId,Map<String,Object> releaseDraft){this(stage,sampleRoundId,releaseDraft,null);}
    }
}
