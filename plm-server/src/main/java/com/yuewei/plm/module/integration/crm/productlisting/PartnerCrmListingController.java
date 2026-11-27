package com.yuewei.plm.module.integration.crm.productlisting;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/partner-operations/catalog/listings")
public class PartnerCrmListingController {
    private final PartnerCrmListingApplicationService service;
    public record Item(@Positive long listingId,@Pattern(regexp="[a-f0-9]{64}") @NotNull String snapshotVersion,@NotEmpty @Size(max=100) List<@NotNull @Positive Long> internalSkuIds) {}
    public record Push(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestId,@NotEmpty @Size(max=20) List<@NotNull @Valid Item> items) {}
    public record Retry(@Positive long taskId,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestId) {}
    private ResponseVO<?> ok(Object data,HttpServletRequest r) { return ResponseVO.success(data,RequestIdUtil.getRequestId(r),OffsetDateTime.now()); }
    @GetMapping("/crm-capabilities") public ResponseVO<?> capabilities(HttpServletRequest r) { return ok(service.capabilities(),r); }
    @GetMapping("/{id}/crm-push-options") public ResponseVO<?> options(@PathVariable long id,HttpServletRequest r) { return ok(service.options(id),r); }
    @PostMapping("/crm-pushes") public ResponseVO<?> push(@Valid @RequestBody Push input,HttpServletRequest r) {
        return ok(service.submit(input.requestId(),input.items().stream().map(i->Map.<String,Object>of("listingId",i.listingId(),"snapshotVersion",i.snapshotVersion(),"internalSkuIds",i.internalSkuIds())).toList()),r);
    }
    @GetMapping("/{id}/crm-sync") public ResponseVO<?> sync(@PathVariable long id,HttpServletRequest r) { return ok(service.sync(id),r); }
    @PostMapping("/{id}/crm-retries") public ResponseVO<?> retry(@PathVariable long id,@Valid @RequestBody Retry input,HttpServletRequest r) { return ok(service.retry(id,input.taskId(),input.requestId()),r); }
}
