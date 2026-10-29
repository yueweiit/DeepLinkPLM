package com.yuewei.plm.module.marketwatch;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;
import jakarta.validation.constraints.*;

public final class MarketWatchTypes {
    private MarketWatchTypes() {}

    @Data public static class ModelQuery {
        @Min(1) private int page=1;
        @Min(1) @Max(100) private int size=50;
        @Size(max=128) private String keyword;
        @Positive private Long brandId;
        @Size(max=32) private String country;
        @Positive private Long productLineId;
        private String coverageState;
        @Pattern(regexp="all|opportunities|to_apply|applied|partially_molded|not_planned|needs_confirmation|applying|making|molded") private String opportunityView;
        @Pattern(regexp="matched|unmatched|ambiguous") private String matchState;
    }
    public record MoldPlan(@NotBlank @Pattern(regexp="planned|not_planned") String status,@NotBlank String originalStatus,@NotBlank @Size(max=1000) String reason) {}
    public record LinkPhoneModel(@NotNull @Positive Long phoneModelId,@Positive Long originalPhoneModelId,@Size(max=1000) String reason) {}
    public record UpdateItem(
        @Size(max = 32) String status,
        @Size(max = 64) String badge,
        @Size(max = 64) String saleStatus
    ) {}

    public record BulkUpdate(
        @NotBlank @Pattern(regexp = "phone|candidate") String kind,
        @NotEmpty @Size(max = 500) List<@Positive Long> ids,
        @Size(max = 32) String status,
        @Size(max = 64) String badge,
        @Size(max = 64) String saleStatus
    ) {}

}
