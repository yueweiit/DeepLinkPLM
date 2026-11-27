package com.yuewei.plm.module.moldsharing;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/mold-share-mappings")
public class MoldShareMappingController {
    private final MoldShareMappingService service;
    private final ExistingMoldGroupMappingService existingGroups;
    private final MoldShareAutoMatchService autoMatch;
    @PostMapping("/auto-match/preview") public ResponseVO<?> previewAutoMatch(@RequestBody MoldShareAutoMatchService.Input input,HttpServletRequest request){return ok(autoMatch.preview(input),request);}
    @PostMapping("/auto-match/confirm") public ResponseVO<?> confirmAutoMatch(@RequestBody MoldShareAutoMatchService.Input input,HttpServletRequest request){return ok(autoMatch.confirm(input,request),request);}
    @GetMapping("/existing-groups") public ResponseVO<?> existingGroups(@RequestParam long productLineId,@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest request){return ok(existingGroups.groups(productLineId,keyword,page,size),request);}
    @PostMapping("/existing-groups/preview") public ResponseVO<?> previewGroup(@RequestBody ExistingMoldGroupMappingService.Input input,HttpServletRequest request){return ok(existingGroups.preview(input),request);}
    @PostMapping("/existing-groups/confirm") public ResponseVO<?> confirmGroup(@RequestBody ExistingMoldGroupMappingService.Input input,HttpServletRequest request){return ok(existingGroups.confirm(input,request),request);}
    private ResponseVO<?> ok(Object value,HttpServletRequest request){return ResponseVO.success(value,RequestIdUtil.getRequestId(request),OffsetDateTime.now());}
    @PostMapping("/resolve") public ResponseVO<?> resolve(@RequestBody MoldShareMappingService.PlanInput input,HttpServletRequest request){return ok(service.resolvePlan(input.productLineId(),input.engineeringPlan()),request);}
    @GetMapping("/entities") public ResponseVO<?> entities(@RequestParam Map<String,String> query,HttpServletRequest request){return ok(service.entityMappings(query),request);}
    @GetMapping("/resolve-code") public ResponseVO<?> resolveCode(@RequestParam String code,HttpServletRequest request){return ok(service.resolveEntityCode(code),request);}
    @PostMapping("/entities/confirm") public ResponseVO<?> confirmEntity(@RequestBody MoldShareMappingService.EntityMappingInput input,HttpServletRequest request){return ok(service.confirmEntityMapping(input,request),request);}
    @PostMapping("/entities/preview") public ResponseVO<?> previewEntity(@RequestBody MoldShareMappingService.EntityMappingInput input,HttpServletRequest request){return ok(service.previewEntityMapping(input),request);}
    @PostMapping("/applications/{id}/confirm") public ResponseVO<?> confirmApplication(@PathVariable long id,@RequestBody MoldShareMappingService.ApplicationConfirmation input,HttpServletRequest request){return ok(service.confirmApprovedApplication(id,input,request),request);}
    @PostMapping("/confirmations") public ResponseVO<?> confirm(@RequestBody MoldShareMappingService.ConfirmationInput input,HttpServletRequest request){return ok(service.confirm(input,request),request);}
    @GetMapping("/confirmations") public ResponseVO<?> confirmations(@RequestParam Map<String,String> query,HttpServletRequest request){return ok(service.confirmations(query),request);}
    @GetMapping("/confirmations/{id}") public ResponseVO<?> confirmation(@PathVariable long id,@RequestParam long productLineId,HttpServletRequest request){return ok(service.confirmation(productLineId,id),request);}
}
