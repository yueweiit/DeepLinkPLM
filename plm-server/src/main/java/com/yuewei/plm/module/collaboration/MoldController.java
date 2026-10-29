package com.yuewei.plm.module.collaboration;

import com.yuewei.plm.common.vo.ResponseVO;
import com.yuewei.plm.common.util.RequestIdUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import static com.yuewei.plm.module.collaboration.CollaborationTypes.*;

@RestController
@Validated
@RequiredArgsConstructor(onConstructor_=@org.springframework.beans.factory.annotation.Autowired)
@RequestMapping("/api/v1/molds")
public class MoldController {
    private final CollaborationService service;
    private final com.yuewei.plm.module.masterdata.ProductMoldLifecycleService lifecycle;
    public MoldController(CollaborationService service){this(service,null);}

    @GetMapping("/masters/{id}/lifecycle") public ResponseVO<?> masterLifecycle(@PathVariable @Positive long id,HttpServletRequest request){return ok(lifecycle.inspect("item",id),request);}
    @PostMapping("/masters/{id}/actions/{action}") public ResponseVO<?> masterAction(@PathVariable @Positive long id,@PathVariable String action,@Valid @RequestBody com.yuewei.plm.module.masterdata.ProductMoldLifecycleService.Action dto,HttpServletRequest request){return ok(lifecycle.perform("item",id,action,dto,request),request);}
    @GetMapping("/{id}/lifecycle") public ResponseVO<?> setLifecycle(@PathVariable @Positive long id,HttpServletRequest request){return ok(lifecycle.inspect("mold_set",id),request);}
    @PostMapping("/{id}/actions/{action}") public ResponseVO<?> setAction(@PathVariable @Positive long id,@PathVariable String action,@Valid @RequestBody com.yuewei.plm.module.masterdata.ProductMoldLifecycleService.Action dto,HttpServletRequest request){return ok(lifecycle.perform("mold_set",id,action,dto,request),request);}

    private <T> ResponseVO<T> ok(T data,HttpServletRequest request) {return ResponseVO.success(data,RequestIdUtil.getRequestId(request),OffsetDateTime.now());}
    @GetMapping public ResponseVO<?> page(@Valid Query query,HttpServletRequest request) {return ok(service.moldPage(query),request);}
    @GetMapping("/options") public ResponseVO<?> options(HttpServletRequest request) {return ok(service.moldOptions(),request);}
    @GetMapping("/masters/{id}") public ResponseVO<?> master(@PathVariable @Positive long id,@RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="50") @Min(1) @Max(100) int size,HttpServletRequest request) {return ok(service.master(id,page,size),request);}
    @PostMapping("/masters") public ResponseVO<?> createMaster(@Valid @RequestBody CreateMaster dto,HttpServletRequest request) {return ok(service.createMaster(dto,request),request);}
    @GetMapping("/masters/{id}/history") public ResponseVO<?> masterHistory(@PathVariable @Positive long id,@RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size,HttpServletRequest request) {return ok(service.history(id,page,size),request);}
    @GetMapping("/masters/{id}/references") public ResponseVO<?> masterReferences(@PathVariable @Positive long id,HttpServletRequest request) {return ok(service.references(id),request);}
    @GetMapping("/{id}") public ResponseVO<?> detail(@PathVariable @Positive long id,HttpServletRequest request) {return ok(service.mold(id),request);}
    @PostMapping public ResponseVO<?> create(@Valid @RequestBody CreateMold dto,HttpServletRequest request) {return ok(service.createMoldSet(dto.itemId(),new CreateMoldSet(dto.setName(),null,null,null,dto.existingPhysical(),dto.reason()),request),request);}
    @PostMapping("/{id}/copies") public ResponseVO<?> copy(@PathVariable @Positive long id,@Valid @RequestBody CreateMoldSet dto,HttpServletRequest request) {return ok(service.copyMold(id,dto,request),request);}
    @PostMapping("/batch-save") public ResponseVO<?> save(@Valid @RequestBody BatchSave dto,HttpServletRequest request) {
        return ok(service.saveMoldLedger(dto,request),request);
    }
    @GetMapping("/{id}/history") public ResponseVO<?> history(@PathVariable @Positive long id,@RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size,HttpServletRequest request) {return ok(service.moldHistory(id,page,size),request);}
    @GetMapping("/{id}/references") public ResponseVO<?> references(@PathVariable @Positive long id,HttpServletRequest request) {return ok(service.moldReferences(id),request);}
    @GetMapping("/inventory-options") public ResponseVO<?> inventories(@RequestParam @Size(min=2,max=128) String keyword,HttpServletRequest request) {return ok(service.inventoryOptions(keyword),request);}
}
