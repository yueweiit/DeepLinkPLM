package com.yuewei.plm.module.manufacturing;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/manufacturing-relations")
public class ManufacturingIdentityController {
    private final ManufacturingIdentityService service;
    private ResponseVO<?> ok(Object value,HttpServletRequest request){return ResponseVO.success(value,RequestIdUtil.getRequestId(request),OffsetDateTime.now());}
    @GetMapping public ResponseVO<?> page(@RequestParam Map<String,String> query,HttpServletRequest request){return ok(service.page(query),request);}
    @GetMapping("/options") public ResponseVO<?> options(@RequestParam Map<String,String> query,HttpServletRequest request){return ok(service.options(query),request);}
    @GetMapping("/{id}") public ResponseVO<?> detail(@PathVariable long id,HttpServletRequest request){return ok(service.detail(id),request);}
    @PostMapping public ResponseVO<?> create(@RequestBody ManufacturingIdentityService.WriteInput input,HttpServletRequest request){return ok(service.save(null,input,request),request);}
    @PutMapping("/{id}") public ResponseVO<?> update(@PathVariable long id,@RequestBody ManufacturingIdentityService.WriteInput input,HttpServletRequest request){return ok(service.save(id,input,request),request);}
    @PostMapping("/resolve") public ResponseVO<?> resolve(@RequestBody ManufacturingIdentityService.ResolveInput input,HttpServletRequest request){return ok(service.resolve(input),request);}
}
