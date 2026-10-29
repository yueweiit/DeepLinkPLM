package com.yuewei.plm.module.moldreference;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import lombok.Data;

public final class PhoneModelReferenceTypes {
    private PhoneModelReferenceTypes() {}

    @Data public static class Query {
        @Min(1) private int page = 1;
        @Min(1) @Max(100) private int size = 50;
        @Size(max=128) private String keyword;
        private boolean selectionSearch;
        private String status;
        private String productionStatus;
        @Positive private Long brandId;
        @Positive private Long codeItemId;
    }
    public record Prefix(@NotBlank @Size(max=128) String prefix,@NotNull @Size(max=128) String replacement) {}
    public record CreateBrand(@NotBlank @Size(max=128) String name, @Size(max=30) List<@NotBlank @Size(max=128) String> aliases,
                              @Size(max=30) List<@jakarta.validation.Valid Prefix> prefixes) {
        public CreateBrand(String name) {this(name,List.of(),List.of());}
        public CreateBrand(String name,List<String> aliases) {this(name,aliases,List.of());}
    }
    public record CreatePhone(@NotNull @Positive Long brandId, @NotBlank @Size(max=256) String modelName,
                              @Size(max=30) List<@NotBlank @Size(max=256) String> aliases,
                              @Positive Long codeItemId) {}
    public record CodeSelectionLink(@NotNull @Positive Long codeItemId,@NotBlank String codeValue,@NotBlank String codeName) {}
    public record CodeSelectionBatch(@NotEmpty @Size(max=1000) List<@jakarta.validation.Valid CodeSelectionLink> items,
                                     @NotBlank @Size(max=1000) String reason) {}
    /** Original values are field-scoped: distinct field edits merge, the same changed field conflicts. */
    public record Patch(@NotEmpty Map<String,JsonNode> changes, @NotNull Map<String,JsonNode> original,
                        @Size(max=1000) String reason) {}
}
