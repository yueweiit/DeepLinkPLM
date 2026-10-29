package com.yuewei.plm.module.moldapplication;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @Validated @RequiredArgsConstructor @RequestMapping("/api/v1/mold-applications")
public class MoldApplicationController {
    private final MoldApplicationService service;private final MoldApplicationConversion conversion;
    private final MoldApplicationMedia media;private final MoldHistoryReconciliationService history;
    private <T> ResponseVO<T> ok(T data,HttpServletRequest request){return ResponseVO.success(data,RequestIdUtil.getRequestId(request),OffsetDateTime.now());}
    public record Version(@Positive long version) {}
    public record CodeSelection(@Positive long version,@Positive Long phoneModelId,@Positive Long itemId,@Positive Long moldSetId,String compatibilityDigest) {}
    public record Range(@NotNull LocalDate from,@NotNull LocalDate until) {}
    public record Config(@NotBlank String tipo,@Positive long productLineId,@Pattern(regexp="[A-Z]{2}") String productSpecificCode,@NotEmpty List<Map<String,String>> materials,Long version) {}
    @GetMapping("/historical-reconciliation") public ResponseVO<?> historyPreview(HttpServletRequest r){return ok(history.preview(),r);}
    @PostMapping("/historical-reconciliation") public ResponseVO<?> historyApply(@Valid @RequestBody MoldHistoryReconciliationService.Apply input,HttpServletRequest r){return ok(history.apply(input,r),r);}
    @GetMapping("/config") public ResponseVO<?> config(HttpServletRequest r){return ok(service.featureConfig(),r);}
    @GetMapping("/options") public ResponseVO<?> options(HttpServletRequest r){return ok(service.options(),r);}
    @GetMapping("/summary") public ResponseVO<?> summary(@RequestParam(required=false) @Positive Long projectId,@RequestParam(required=false) Boolean unlinked,HttpServletRequest r){return ok(service.summary(projectId,unlinked),r);}
    @GetMapping("/phone-codes") public ResponseVO<?> phones(@RequestParam String keyword,HttpServletRequest r){return ok(service.phoneCodes(keyword),r);}
    @GetMapping public ResponseVO<?> page(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size,
        @RequestParam(required=false) String keyword,@RequestParam(required=false) String tipo,@RequestParam(required=false) String status,
        @RequestParam(required=false) String originator,@RequestParam(required=false) String from,@RequestParam(required=false) String until,@RequestParam(required=false) Long projectId,@RequestParam(required=false) Boolean unlinked,HttpServletRequest r){return ok(service.page(page,size,keyword,tipo,status,originator,from,until,projectId,unlinked),r);}
    @GetMapping("/target-options") public ResponseVO<?> targets(@RequestParam long phoneModelId,@RequestParam long productLineId,HttpServletRequest r){return ok(service.targetOptions(phoneModelId,productLineId),r);}
    @GetMapping("/research-context") public ResponseVO<?> researchContext(@RequestParam @Positive long projectId,@RequestParam(required=false) @Positive Long phoneModelId,@RequestParam(required=false) Long version,HttpServletRequest r){return ok(service.researchContext(projectId,phoneModelId,version),r);}
    @PostMapping("/from-research") public ResponseVO<?> fromResearch(@RequestBody MoldApplicationService.ResearchDraft body,HttpServletRequest r){return ok(service.startFromResearch(body,r),r);}
    @PostMapping("/from-phone") public ResponseVO<?> fromPhone(@RequestBody MoldApplicationService.SourceDraft body,HttpServletRequest r){return ok(service.startFromPhone(body,r),r);}
    @PostMapping("/{id}/provision-targets") public ResponseVO<?> targets(@PathVariable @Positive long id,@RequestBody MoldApplicationService.TargetCorrection body,HttpServletRequest r){return ok(service.correctTargets(id,body,r),r);}
    @PostMapping("/{id}/provision-retry") public ResponseVO<?> retry(@PathVariable @Positive long id,@Valid @RequestBody Version body,HttpServletRequest r){return ok(service.retryProvision(id,body.version(),r),r);}
    @PostMapping("/{id}/execution-resolution") public ResponseVO<?> resolve(@PathVariable @Positive long id,@RequestBody MoldApplicationService.Resolution body,HttpServletRequest r){return ok(service.resolve(id,body,r),r);}
    @PostMapping public ResponseVO<?> create(@RequestBody MoldApplicationService.Draft body,HttpServletRequest r){return ok(service.save(null,body,r),r);}
    @GetMapping("/{id}") public ResponseVO<?> detail(@PathVariable @Positive long id,HttpServletRequest r){return ok(service.detail(id),r);}
    @DeleteMapping("/{id}") public ResponseVO<?> deleteDraft(@PathVariable @Positive long id,@RequestParam @Positive long version,HttpServletRequest r){return ok(service.deleteDraft(id,version,r),r);}
    @PutMapping("/{id}") public ResponseVO<?> save(@PathVariable @Positive long id,@RequestBody MoldApplicationService.Draft body,HttpServletRequest r){return ok(service.save(id,body,r),r);}
    @PostMapping("/{id}/codes") public ResponseVO<?> codes(@PathVariable @Positive long id,@Valid @RequestBody CodeSelection v,HttpServletRequest r){return ok(service.reserve(id,v.version(),v.phoneModelId(),v.itemId(),v.moldSetId(),v.compatibilityDigest(),r),r);}
    @PostMapping("/{id}/submit") public ResponseVO<?> submit(@PathVariable @Positive long id,@RequestBody MoldApplicationService.Submit body,HttpServletRequest r){return ok(service.submit(id,body,r),r);}
    @PostMapping("/{id}/withdraw") public ResponseVO<?> withdraw(@PathVariable @Positive long id,@Valid @RequestBody Version v,HttpServletRequest r){return ok(service.withdraw(id,v.version(),r),r);}
    @PostMapping("/{id}/sync") public ResponseVO<?> sync(@PathVariable @Positive long id,HttpServletRequest r){return ok(service.syncOne(id),r);}
    @PostMapping("/sync") public ResponseVO<?> sync(@Valid @RequestBody Range range,HttpServletRequest r){if(range.until().isBefore(range.from())||range.from().isBefore(LocalDate.of(2026,1,1)))throw MoldApplicationForms.invalid("同步日期范围无效");return ok(service.syncRange(range.from(),range.until()),r);}
    @PutMapping("/configs") public ResponseVO<?> configure(@Valid @RequestBody Config c,HttpServletRequest r){service.saveConfig(c.tipo(),c.productLineId(),c.productSpecificCode(),c.materials(),c.version(),r);return ok(Map.of("saved",true),r);}
    @PostMapping("/{id}/conversion-preview") public ResponseVO<?> preview(@PathVariable @Positive long id,HttpServletRequest r){return ok(conversion.preview(id),r);}
    @PostMapping("/{id}/convert") public ResponseVO<?> convert(@PathVariable @Positive long id,@RequestBody MoldApplicationConversion.Convert c,HttpServletRequest r){return ok(conversion.convert(id,c,r),r);}
    @PostMapping("/{id}/historical-association-preview") public ResponseVO<?> historyPreview(@PathVariable @Positive long id,@RequestBody MoldApplicationConversion.AssociationPreview selection,HttpServletRequest r){return ok(conversion.associationPreview(id,selection),r);}
    @PostMapping("/{id}/historical-association") public ResponseVO<?> historyAssociate(@PathVariable @Positive long id,@RequestBody MoldApplicationConversion.Association selection,HttpServletRequest r){return ok(conversion.associateHistory(id,selection,r),r);}
    @PostMapping(value="/{id}/files",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseVO<?> upload(@PathVariable @Positive long id,@RequestParam String category,@RequestParam MultipartFile file,@RequestParam(required=false) String selectionId,HttpServletRequest r){return ok(service.upload(id,category,file,selectionId,r),r);}
    @PostMapping("/{id}/file-selections") public ResponseVO<?> select(@PathVariable @Positive long id,@RequestBody List<MoldApplicationMedia.Selection> s,HttpServletRequest r){return ok(service.selectFiles(id,s,r),r);}
    @PostMapping("/{id}/files/{attachmentId}/discard-selection") public ResponseVO<?> discard(@PathVariable @Positive long id,@PathVariable @Positive long attachmentId,HttpServletRequest r){return ok(service.discardSelection(id,attachmentId,r),r);}
    @GetMapping("/{id}/files/{attachmentId}") public ResponseEntity<byte[]> file(@PathVariable @Positive long id,@PathVariable @Positive long attachmentId){
        byte[] data=service.download(id,attachmentId);var f=media.list(id).stream().filter(x->((Number)x.get("attachmentId")).longValue()==attachmentId).findFirst().orElseThrow();
        return ResponseEntity.ok().header("X-Content-Type-Options","nosniff").header("Cache-Control","private, no-store")
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(f.get("fileName").toString(),java.nio.charset.StandardCharsets.UTF_8).build().toString())
            .contentType(MediaType.parseMediaType(Objects.toString(f.get("contentType"),"application/octet-stream"))).body(data);
    }
}
