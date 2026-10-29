package com.yuewei.plm.module.partner;

import com.yuewei.plm.common.vo.ResponseVO;
import com.yuewei.plm.common.util.RequestIdUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/sample-identities")
public class SampleIdentityController {
 private final SampleIdentityRegistry identities;private final SampleIdentityActions actions;private final PartnerSampleService samples;private final PartnerVisibility visibility;
 private Object ok(Object data,HttpServletRequest r){return ResponseVO.success(visibility.redact(data),RequestIdUtil.getRequestId(r),OffsetDateTime.now());}
 @GetMapping public Object page(@RequestParam Map<String,String> query,HttpServletRequest r){return ok(identities.page(query),r);}
 @GetMapping("/resolve") public Object resolve(@RequestParam String code,HttpServletRequest r){return ok(identities.resolve(code),r);}
 @GetMapping("/candidates") public Object candidates(@RequestParam Map<String,String> input,HttpServletRequest r){var request=new LinkedHashMap<String,Object>(input);if(number(input.get("batchId"))>0){var b=samples.detail(number(input.get("batchId")));return ok(identities.candidates(b,request),r);}return ok(identities.candidates(samples.identitySource(request),request),r);}
 @PostMapping("/bulk-actions/{action}") public Object bulk(@PathVariable String action,@RequestBody Map<String,Object> input,HttpServletRequest r){return ok(actions.bulk(action,input),r);}
 @GetMapping("/{id}/colors") public Object colors(@PathVariable long id,HttpServletRequest r){return ok(actions.colorOptions(id),r);}
 @PostMapping("/{id}/color-correction-preview") public Object previewColor(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest r){return ok(actions.previewColor(id,input),r);}
 @GetMapping("/{id}") public Object detail(@PathVariable long id,HttpServletRequest r){return ok(identities.detail(id),r);}
 @PostMapping public Object create(@RequestBody Map<String,Object> input,HttpServletRequest r){return ok(identities.ensure(samples.identitySource(input),input),r);}
 @PostMapping("/{id}/actions/{action}") public Object action(@PathVariable long id,@PathVariable String action,@RequestBody Map<String,Object> input,HttpServletRequest r){return ok(actions.action(id,action,input),r);}
 @PostMapping("/batches/{batchId}/identity") public Object bind(@PathVariable long batchId,@RequestBody Map<String,Object> input,HttpServletRequest r){return ok(actions.bind(batchId,input),r);}
}
