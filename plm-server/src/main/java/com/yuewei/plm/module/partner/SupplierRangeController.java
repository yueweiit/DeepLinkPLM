package com.yuewei.plm.module.partner;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/inventories/suppliers/{code}")
public class SupplierRangeController {
    private final SupplierRangeService service;
    private ResponseVO<?> ok(Object value,HttpServletRequest request){return ResponseVO.success(value,RequestIdUtil.getRequestId(request),OffsetDateTime.now());}
    @GetMapping("/colors") public ResponseVO<?> colors(@PathVariable String code,@RequestParam Map<String,String> query,HttpServletRequest r){return ok(service.colors(code,query),r);}
    @GetMapping("/ranges") public ResponseVO<?> ranges(@PathVariable String code,@RequestParam Map<String,String> query,HttpServletRequest r){return ok(service.ranges(code,query),r);}
    @PostMapping("/colors/preview") public ResponseVO<?> previewColors(@PathVariable String code,@RequestBody Map<String,Object> input,HttpServletRequest r){return ok(service.previewColors(code,input),r);}
    @PostMapping("/colors/batch") public ResponseVO<?> colorsBatch(@PathVariable String code,@RequestBody Map<String,Object> input,HttpServletRequest r){return ok(service.saveColors(code,input),r);}
    @PostMapping("/ranges/preview") public ResponseVO<?> preview(@PathVariable String code,@RequestBody Map<String,Object> input,HttpServletRequest r){return ok(service.preview(code,input),r);}
    @PostMapping("/ranges/batch") public ResponseVO<?> rangesBatch(@PathVariable String code,@RequestBody Map<String,Object> input,HttpServletRequest r){return ok(service.saveRanges(code,input),r);}
    @PostMapping("/ranges/resolve") public ResponseVO<?> resolve(@PathVariable String code,@RequestBody Map<String,Object> input,HttpServletRequest r){return ok(service.resolve(code,input),r);}
    @PatchMapping("/metadata") public ResponseVO<?> metadata(@PathVariable String code,@RequestBody Map<String,Object> input,HttpServletRequest r){return ok(service.metadata(code,input),r);}
}
