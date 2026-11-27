package com.yuewei.plm.module.moldsharing;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/finished-product-mappings")
public class FinishedProductMappingController {
    private final FinishedProductMappingService service;
    private ResponseVO<?> ok(Object value,HttpServletRequest request){return ResponseVO.success(value,RequestIdUtil.getRequestId(request),OffsetDateTime.now());}
    @GetMapping public ResponseVO<?> page(@RequestParam Map<String,String> query,HttpServletRequest request){return ok(service.page(query),request);}
}
