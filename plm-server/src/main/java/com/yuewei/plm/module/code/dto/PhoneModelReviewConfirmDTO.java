package com.yuewei.plm.module.code.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** One explicitly reviewed historical group; preserving a combination never requires a single phone link. */
public record PhoneModelReviewConfirmDTO(
    @NotBlank @Pattern(regexp="code_many_names|name_many_codes") String kind,
    @NotBlank @Size(max=256) String key,
    @NotBlank @Pattern(regexp="[0-9a-f]{32}") String sourceDigest,
    @NotBlank @Pattern(regexp="same_phone_alias|shared_code_distinct_phones|shared_mold_different_codes|combined_original_preserved") String conclusion,
    @NotBlank @Size(max=2000) String reason,
    @Size(max=100) List<@Positive Long> phoneModelIds,
    @Size(max=100) List<@Valid ProjectLink> projectLinks
) {
    public record ProjectLink(@NotNull @Positive Long projectId,@Positive Long originalPhoneModelId,@NotNull @Positive Long phoneModelId) {}
}
