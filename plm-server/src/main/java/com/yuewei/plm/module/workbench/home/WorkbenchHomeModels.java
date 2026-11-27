package com.yuewei.plm.module.workbench.home;

import com.yuewei.plm.common.vo.PageVO;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.*;
import lombok.Data;

public final class WorkbenchHomeModels {
    private WorkbenchHomeModels() {}

    @Data public static class Query {
        @Min(1) private int page = 1;
        @Min(1) @Max(100) private int size = 20;
        @Size(max=128) private String keyword;
        @Pattern(regexp="mine|visible") private String scope = "mine";
        @Size(max=32) private String source = "schedule";
        private boolean overdueOnly;
    }

    public record SourceCount(String source, String state, Long total, String readAt, String sourceUpdatedAt) {}
    public record HomePage(List<Map<String,Object>> content, int page, int size, Long totalElements,
                           Long totalPages, String source, String sourceState, String readAt, String sourceUpdatedAt) {
        public SourceCount count() { return new SourceCount(source, sourceState, totalElements, readAt, sourceUpdatedAt); }
        public HomePage state(String value) { return new HomePage(content,page,size,totalElements,totalPages,source,value,readAt,sourceUpdatedAt); }
    }
    public static HomePage page(String source, Query query, List<Map<String,Object>> rows, long total, String updatedAt) {
        return new HomePage(rows,query.getPage(),query.getSize(),total,(total+query.getSize()-1)/query.getSize(),
            source,"ok",OffsetDateTime.now().toString(),updatedAt);
    }
    public static HomePage page(String source, Query query, PageVO<Map<String,Object>> value) {
        return page(source,query,value.getContent(),value.getTotalElements(),null);
    }
    public static HomePage unavailable(String source, Query query, String state) {
        return new HomePage(List.of(),query.getPage(),query.getSize(),null,null,source,state,OffsetDateTime.now().toString(),null);
    }
}
