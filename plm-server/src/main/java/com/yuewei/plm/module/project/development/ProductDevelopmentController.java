package com.yuewei.plm.module.project.development;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import static com.yuewei.plm.module.project.development.DevelopmentModels.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/product-research")
public class ProductDevelopmentController {
    private final com.yuewei.plm.module.attachment.service.DocumentReferenceBrowser references;
    private final ProductDevelopmentService service;
    private final ProductResearchBusinessService business;
    private final com.yuewei.plm.service.ProductService productService;
    @ModelAttribute
    void requireCurrentClient(HttpServletRequest request){
        String method=request.getMethod(),path=request.getRequestURI();
        if(java.util.Set.of("POST","PUT","DELETE","PATCH").contains(method)&&!path.endsWith("-readiness")&&!path.endsWith("/preview"))
            DevelopmentRules.require("eight_step".equals(request.getHeader("X-PLM-Research-Model")),"新品研发流程已更新，请保存本地输入并刷新页面后重新办理");
    }
    private ResponseVO<?> ok(Object value, HttpServletRequest request) {
        return ResponseVO.success(value, RequestIdUtil.getRequestId(request), OffsetDateTime.now());
    }
    @GetMapping("/reference-options/page") public ResponseVO<?> referencePage(com.yuewei.plm.module.attachment.service.DocumentReferenceBrowser.Query query,HttpServletRequest request) {return ok(references.researchPage(query),request);}
    @GetMapping("/reference-options/phones") public ResponseVO<?> referencePhones(com.yuewei.plm.module.attachment.service.DocumentReferenceBrowser.Query query,HttpServletRequest request) {return ok(references.researchPhones(query),request);}
    @GetMapping
    public ResponseVO<?> page(@RequestParam(defaultValue = "") String keyword,
        @RequestParam(defaultValue = "active") String state, @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "50") int size, @RequestParam(required = false) Integer stage, @RequestParam(defaultValue="launch_v1") String flow, HttpServletRequest request) {
        return ok(service.page(keyword, state, page, size, stage,flow), request);
    }
    @PostMapping
    public ResponseVO<?> create(@Valid @RequestBody com.yuewei.plm.controller.dto.ProductCreateDTO input,
        HttpServletRequest request) {
        input.setProductType("product_line");
        input.setDevelopmentMode("launch_v1");
        return ok(productService.create(input), request);
    }
    @GetMapping("/business")
    public ResponseVO<?> business(@RequestParam String kind,@RequestParam(defaultValue="") String keyword,
        @RequestParam(defaultValue="all") String status,@RequestParam(required=false) Long projectId,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="30") int size,HttpServletRequest request) {
        return ok(business.page(kind,keyword,status,projectId,page,size),request);
    }
    @GetMapping("/options")
    public ResponseVO<?> options(HttpServletRequest request) { return ok(service.ownerOptions(),request); }
    @PostMapping("/{id}/workflow")
    public ResponseVO<?> workflow(@PathVariable long id,@Valid @RequestBody WorkflowInput input,HttpServletRequest request) { return ok(service.workflowAction(id,input),request); }
    @GetMapping("/{id}")
    public ResponseVO<?> detail(@PathVariable long id, HttpServletRequest request) { return ok(service.detail(id), request); }
    @GetMapping("/{id}/logs")
    public ResponseVO<?> logs(@PathVariable long id,@Valid com.yuewei.plm.module.operationlog.dto.OperationLogQueryDTO query,HttpServletRequest request){return ok(service.projectLogs(id,query),request);}
    @PutMapping("/{id}")
    public ResponseVO<?> profile(@PathVariable long id, @Valid @RequestBody ProfileInput input, HttpServletRequest request) {
        return ok(service.updateProfile(id, input), request);
    }
    @PutMapping("/{id}/design")
    public ResponseVO<?> design(@PathVariable long id,@Valid @RequestBody DesignDraftInput input,HttpServletRequest request) {return ok(service.saveDesignDraft(id,input),request);}
    @PostMapping("/{id}/design-readiness")
    public ResponseVO<?> designReadiness(@PathVariable long id,@RequestBody DesignDraftInput input,HttpServletRequest request) {return ok(service.designReadiness(id,input),request);}
    @PostMapping("/{id}/stage-readiness")
    public ResponseVO<?> stageReadiness(@PathVariable long id,@RequestBody StageReadinessInput input,HttpServletRequest request) {return ok(service.stageReadiness(id,input),request);}
    @DeleteMapping("/{id}")
    public ResponseVO<?> deleteProject(@PathVariable long id,@RequestParam Long version,HttpServletRequest request) {
        service.deleteProjectDraft(id,version);return ok(null,request);
    }
    @PostMapping("/{id}/unit-cost/preview")
    public ResponseVO<?> previewCost(@PathVariable long id,@RequestBody java.util.Map<String,Object> input,HttpServletRequest request) {
        return ok(service.previewUnitCost(id,input),request);
    }
    @PostMapping("/{id}/rounds")
    public ResponseVO<?> createRound(@PathVariable long id, @Valid @RequestBody RoundInput input, HttpServletRequest request) {
        return ok(service.saveRound(id, null, input), request);
    }
    @PutMapping("/{id}/rounds/{roundId}")
    public ResponseVO<?> updateRound(@PathVariable long id, @PathVariable long roundId,
        @Valid @RequestBody RoundInput input, HttpServletRequest request) {
        return ok(service.saveRound(id, roundId, input), request);
    }
    @DeleteMapping("/{id}/rounds/{roundId}")
    public ResponseVO<?> deleteDraft(@PathVariable long id, @PathVariable long roundId,@RequestParam(required=false) Long version, HttpServletRequest request) {
        service.deleteDraft(id, roundId,version); return ok(null, request);
    }
}
