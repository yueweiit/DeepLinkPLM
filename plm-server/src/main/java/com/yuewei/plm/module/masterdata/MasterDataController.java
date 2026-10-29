package com.yuewei.plm.module.masterdata;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import com.yuewei.plm.module.pilot.PilotAccessService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1") @RequiredArgsConstructor
public class MasterDataController {
    private final MasterDataCatalogService catalog;private final MasterDataImportService imports;private final MasterDataSnapshotStore store;private final PilotAccessService access;
    @GetMapping("/master-data/status") public ResponseVO<?> status(HttpServletRequest request){return response(catalog.status(),request);}
    @GetMapping("/master-data/snapshots") public ResponseVO<?> snapshots(HttpServletRequest request){access.requireAdministrator();return response(store.available(),request);}
    @PostMapping("/master-data/preview") public ResponseVO<?> preview(@RequestBody MasterDataImportService.PreviewRequest input,HttpServletRequest request){return response(imports.preview(input),request);}
    @PostMapping("/master-data/commit") public ResponseVO<?> commit(@RequestBody MasterDataImportService.CommitRequest input,HttpServletRequest request){return response(imports.commit(input,request),request);}
    @GetMapping("/products/current-catalog") public ResponseVO<?> skus(MasterDataCatalogService.Query query,HttpServletRequest request){return response(catalog.skus(query),request);}
    @GetMapping("/products/current-catalog/options") public ResponseVO<?> options(HttpServletRequest request){return response(catalog.options(),request);}
    @GetMapping("/process-routes/current") public ResponseVO<?> processes(HttpServletRequest request){return response(catalog.processes(),request);}
    @GetMapping("/products/current-catalog/{sourceId}") public ResponseVO<?> sku(@PathVariable String sourceId,HttpServletRequest request){return response(catalog.sku(sourceId),request);}
    @GetMapping("/process-routes/{routeId}/graph") public ResponseVO<?> graph(@PathVariable long routeId,HttpServletRequest request){access.requireDomesticView();access.requirePermission("process:view");return response(catalog.graph(routeId),request);}
    private ResponseVO<?> response(Object result,HttpServletRequest request){return ResponseVO.success(result,RequestIdUtil.getRequestId(request),OffsetDateTime.now());}
}
