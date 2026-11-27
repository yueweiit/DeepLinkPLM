package com.yuewei.plm.module.moldsharing;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Registry-only endpoints, independent of adoption and approval services. */
@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/mold-share-mappings")
public class MoldShareMappingRegistryController {
    private final MoldShareMappingRegistryService registry;
    private ResponseVO<?> ok(Object value,HttpServletRequest request){return ResponseVO.success(value,RequestIdUtil.getRequestId(request),OffsetDateTime.now());}
    @GetMapping public ResponseVO<?> page(@RequestParam Map<String,String> query,HttpServletRequest request){return ok(registry.page(query),request);}
    @PostMapping public ResponseVO<?> create(@RequestBody MoldShareMappingRegistryService.WriteInput input,HttpServletRequest request){return ok(registry.save(null,input,request),request);}
    @PutMapping("/{id}") public ResponseVO<?> update(@PathVariable long id,@RequestBody MoldShareMappingRegistryService.WriteInput input,HttpServletRequest request){return ok(registry.save(id,input,request),request);}
}
