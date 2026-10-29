package com.yuewei.plm.module.productionmetadata;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import com.yuewei.plm.module.moldreference.PhoneModelReferenceTypes.Patch;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @Validated @RequiredArgsConstructor @RequestMapping("/api/v1")
public class ProductionMetadataController {
    private final ProductionMetadataService service;
    private <T> ResponseVO<T> ok(T value,HttpServletRequest request){return ResponseVO.success(value,RequestIdUtil.getRequestId(request),OffsetDateTime.now());}
    @GetMapping("/products/sales-catalog") public ResponseVO<?> salesCatalog(HttpServletRequest r){return ok(service.salesCatalog(),r);}
    @GetMapping("/production-metadata/options") public ResponseVO<?> options(HttpServletRequest r){return ok(service.options(),r);}
    @PatchMapping("/code-items/{id}/phone-metadata") public ResponseVO<?> phone(@PathVariable @Positive long id,@Valid @RequestBody Patch patch,HttpServletRequest r){return ok(service.phone(id,patch,r),r);}
    @PostMapping("/code-items/phone-metadata/batch") public ResponseVO<?> phones(@Valid @RequestBody ProductionMetadataService.Batch batch,HttpServletRequest r){return ok(service.phoneBatch(batch,r),r);}
    @GetMapping("/code-items/phone-metadata/brand-review") public ResponseVO<?> phoneBrandReview(HttpServletRequest r){return ok(service.phoneBrandReview(),r);}
    @PostMapping("/code-items/phone-metadata/brand-review/apply") public ResponseVO<?> applyPhoneBrandReview(@Valid @RequestBody ProductionMetadataService.BrandReviewBatch batch,HttpServletRequest r){return ok(service.applyPhoneBrandReview(batch,r),r);}
    @PatchMapping("/products/{id}/production-status") public ResponseVO<?> product(@PathVariable @Positive long id,@Valid @RequestBody Patch patch,HttpServletRequest r){return ok(service.product(id,patch,r),r);}
    @PostMapping("/products/production-status/batch") public ResponseVO<?> products(@Valid @RequestBody ProductionMetadataService.Batch batch,HttpServletRequest r){return ok(service.productBatch(batch,r),r);}
}
