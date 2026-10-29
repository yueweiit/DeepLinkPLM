package com.yuewei.plm.module.marketwatch;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import com.yuewei.plm.module.marketwatch.MarketWatchTypes.BulkUpdate;
import com.yuewei.plm.module.marketwatch.MarketWatchTypes.UpdateItem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/market-watch")
public class MarketWatchController {
    private final MarketWatchService service;
    private final MarketWatchWorkflowService workflow;

    private <T> ResponseVO<T> ok(T data,HttpServletRequest request) {
        return ResponseVO.success(data,RequestIdUtil.getRequestId(request),OffsetDateTime.now());
    }

    @GetMapping("/items") public ResponseVO<?> items(@Valid MarketWatchTypes.ModelQuery query,HttpServletRequest request) {return ok(workflow.page(query),request);}
    @GetMapping("/items/{rowKey}") public ResponseVO<?> model(@PathVariable String rowKey,HttpServletRequest request) {return ok(workflow.detail(rowKey),request);}
    @PostMapping("/prices/refresh") public ResponseVO<?> pricesRefresh(HttpServletRequest r){return ok(workflow.refreshPrices(true),r);}
    @GetMapping("/prices/refresh") public ResponseVO<?> pricesStatus(HttpServletRequest r){return ok(workflow.refreshPrices(false),r);}
    @PostMapping("/refresh") public ResponseVO<?> refresh(HttpServletRequest request) {return ok(workflow.refresh(),request);}
    @GetMapping("/reconcile-preview") public ResponseVO<?> identityPreview(HttpServletRequest r){return ok(workflow.identityCandidates(),r);}
    @PostMapping("/reconcile") public ResponseVO<?> reconcile(HttpServletRequest request) {return ok(workflow.reconcile(request),request);}
    @PatchMapping("/items/{kind}/{id}/phone-model") public ResponseVO<?> linkPhone(@PathVariable @Pattern(regexp="phone|candidate") String kind,@PathVariable @Positive long id,@Valid @RequestBody MarketWatchTypes.LinkPhoneModel command,HttpServletRequest request) {return ok(workflow.link(kind,id,command,request),request);}
    @PatchMapping("/phones/{phoneId}/mold-plan") public ResponseVO<?> plan(@PathVariable @Positive long phoneId,@Valid @RequestBody MarketWatchTypes.MoldPlan input,HttpServletRequest r){return ok(workflow.moldPlan(phoneId,input,r),r);}
    @GetMapping("/config") public ResponseVO<?> config(HttpServletRequest request) {return ok(service.config(),request);}
    @GetMapping("/health") public ResponseVO<?> health(HttpServletRequest request) {return ok(service.health(),request);}
    @GetMapping("/summary") public ResponseVO<?> summary(@Valid MarketWatchTypes.ModelQuery query,HttpServletRequest request) {return ok(workflow.summary(query),request);}
    @GetMapping("/items/{kind}/{id}") public ResponseVO<?> detail(@PathVariable @Pattern(regexp="phone|candidate") String kind,@PathVariable @Positive long id,HttpServletRequest request) {return ok(workflow.sourceDetail(kind,id),request);}
    @PatchMapping("/items/{kind}/{id}") public ResponseVO<?> update(@PathVariable @Pattern(regexp="phone|candidate") String kind,@PathVariable @Positive long id,@Valid @RequestBody UpdateItem body,HttpServletRequest request) {return ok(service.update(kind,id,body),request);}
    @PostMapping("/items/bulk") public ResponseVO<?> bulk(@Valid @RequestBody BulkUpdate body,HttpServletRequest request) {return ok(service.bulk(body),request);}

    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> export(@RequestParam MultiValueMap<String,String> query) {
        MarketWatchClient.CsvFile file=service.export(query);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.filename()).build().toString())
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .body(file.bytes());
    }
}
