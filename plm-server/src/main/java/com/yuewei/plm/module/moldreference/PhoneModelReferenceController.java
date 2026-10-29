package com.yuewei.plm.module.moldreference;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import static com.yuewei.plm.module.moldreference.PhoneModelReferenceTypes.*;

@RestController @Validated @RequiredArgsConstructor
@RequestMapping("/api/v1/molds/reference-data")
public class PhoneModelReferenceController {
    private final PhoneModelReferenceService service;
    private final PhoneModelCoverageService coverage;
    @org.springframework.beans.factory.annotation.Autowired private com.yuewei.plm.module.code.service.CodeLifecycleService lifecycle;
    @org.springframework.beans.factory.annotation.Autowired
    private PhoneIdentityIssuesService identityIssues;
    private <T> ResponseVO<T> ok(T value,HttpServletRequest request) {return ResponseVO.success(value,RequestIdUtil.getRequestId(request),OffsetDateTime.now());}
    @GetMapping("/brands") public ResponseVO<?> brands(@Valid Query query,HttpServletRequest request) {return ok(service.brands(query),request);}
    @GetMapping("/brands/{id}") public ResponseVO<?> brand(@PathVariable @Positive long id,HttpServletRequest request) {return ok(service.brand(id),request);}
    @PostMapping("/brands") public ResponseVO<?> createBrand(@Valid @RequestBody CreateBrand dto,HttpServletRequest request) {return ok(service.createBrand(dto,request),request);}
    @PatchMapping("/brands/{id}") public ResponseVO<?> patchBrand(@PathVariable @Positive long id,@Valid @RequestBody Patch dto,HttpServletRequest request) {rejectStatusPatch(dto);return ok(service.patchBrand(id,dto,request),request);}
    @GetMapping("/brands/{id}/history") public ResponseVO<?> brandHistory(@PathVariable @Positive long id,@Valid Query q,HttpServletRequest request) {return ok(service.history("brand",id,q),request);}
    @GetMapping("/phone-models") public ResponseVO<?> phones(@Valid Query query,HttpServletRequest request) {return ok(service.phones(query),request);}
    @GetMapping("/phone-models/brands") public ResponseVO<?> selectionBrands(HttpServletRequest request) {return ok(service.selectionBrands(),request);}
    @GetMapping("/phone-models/issues") public ResponseVO<?> issues(@Valid PhoneIdentityIssuesService.Query query,HttpServletRequest request) {return ok(identityIssues.page(query),request);}
    @PostMapping("/phone-models/issues/resolve") public ResponseVO<?> resolveIssues(@Valid @RequestBody PhoneIdentityIssuesService.Resolve input,HttpServletRequest request) {return ok(identityIssues.resolve(input,request),request);}
    @PostMapping("/phone-models/issues/edit-code") public ResponseVO<?> editIssueCode(@Valid @RequestBody PhoneIdentityIssuesService.CodeEdit input,HttpServletRequest request) {return ok(identityIssues.editCode(input,request),request);}
    @PostMapping("/brands/merge-preview") public ResponseVO<?> mergePreview(@Valid @RequestBody PhoneIdentityIssuesService.BrandMerge input,HttpServletRequest request) {return ok(identityIssues.previewBrandMerge(input),request);}
    @PostMapping("/brands/merge") public ResponseVO<?> mergeBrands(@Valid @RequestBody PhoneIdentityIssuesService.BrandMerge input,HttpServletRequest request) {return ok(identityIssues.mergeBrands(input,request),request);}
    @GetMapping("/phone-models/options") public ResponseVO<?> options(@RequestParam List<Long> ids,HttpServletRequest request) {return ok(service.options(ids),request);}
    @GetMapping("/phone-models/{id}") public ResponseVO<?> phone(@PathVariable @Positive long id,HttpServletRequest request) {return ok(service.phone(id),request);}
    @GetMapping("/phone-models/{id}/coverage") public ResponseVO<?> coverage(@PathVariable @Positive long id,HttpServletRequest request) {service.phone(id);return ok(coverage.batch(List.of(id)).getOrDefault(id,List.of()),request);}
    @GetMapping("/phone-models/{id}/molds") public ResponseVO<?> compatibleMolds(@PathVariable @Positive long id,@RequestParam @Positive long productLineId,HttpServletRequest request) {return ok(service.compatibleMolds(id,productLineId),request);}
    @PostMapping("/phone-models") public ResponseVO<?> createPhone(@Valid @RequestBody CreatePhone dto,HttpServletRequest request) {return ok(service.createPhone(dto,request),request);}
    @PostMapping("/phone-models/code-selection-links") public ResponseVO<?> linkCodes(@Valid @RequestBody CodeSelectionBatch dto,HttpServletRequest request) {return ok(service.linkCodeSelection(dto,request),request);}
    @PatchMapping("/phone-models/{id}") public ResponseVO<?> patchPhone(@PathVariable @Positive long id,@Valid @RequestBody Patch dto,HttpServletRequest request) {rejectStatusPatch(dto);return ok(service.patchPhone(id,dto,request),request);}
    @GetMapping("/phone-models/{id}/history") public ResponseVO<?> phoneHistory(@PathVariable @Positive long id,@Valid Query q,HttpServletRequest request) {return ok(service.history("phone",id,q),request);}
    @GetMapping("/brands/{id}/references") public ResponseVO<?> brandReferences(@PathVariable long id,HttpServletRequest request){return ok(lifecycle.inspect("brand",id),request);}
    @GetMapping("/phone-models/{id}/references") public ResponseVO<?> phoneReferences(@PathVariable long id,HttpServletRequest request){return ok(lifecycle.inspect("phone",id),request);}
    @PostMapping("/brands/{id}/actions/{action}") public ResponseVO<?> brandAction(@PathVariable long id,@PathVariable String action,@Valid @RequestBody com.yuewei.plm.module.code.service.CodeLifecycleService.Action input,HttpServletRequest request){return ok(lifecycle.act("brand",id,action,input,request),request);}
    @PostMapping("/phone-models/{id}/actions/{action}") public ResponseVO<?> phoneAction(@PathVariable long id,@PathVariable String action,@Valid @RequestBody com.yuewei.plm.module.code.service.CodeLifecycleService.Action input,HttpServletRequest request){return ok(lifecycle.act("phone",id,action,input,request),request);}

    private void rejectStatusPatch(Patch dto){if(dto.changes()!=null&&dto.changes().containsKey("status"))throw new com.yuewei.plm.common.exception.BusinessException(40901,"请刷新页面，使用启停操作并填写原因");}

}
