package com.yuewei.plm.module.moldreference;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/molds/reference-data/phone-models/intake")
public class PhoneModelIntakeController {
    private final PhoneModelIntakeService service;
    private ResponseVO<?> ok(Object data,HttpServletRequest request){return ResponseVO.success(data,RequestIdUtil.getRequestId(request),OffsetDateTime.now());}
    @GetMapping public ResponseVO<?> page(@RequestParam Map<String,String> query,HttpServletRequest request){return ok(service.page(query),request);}
    @GetMapping("/{id}") public ResponseVO<?> detail(@PathVariable long id,HttpServletRequest request){return ok(service.detail(id),request);}
    @GetMapping("/{id}/sources") public ResponseVO<?> sources(@PathVariable long id,@RequestParam Map<String,String> query,HttpServletRequest request){return ok(service.sources(id,query),request);}
    @PostMapping("/source-preview") public ResponseVO<?> sourcePreview(@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(service.sourcePreview(input),request);}
    @PostMapping("/sources") public ResponseVO<?> collect(@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(service.collect(input,request),request);}
    @PostMapping("/{id}/resolution-preview") public ResponseVO<?> resolutionPreview(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(service.resolutionPreview(id,input),request);}
    @PostMapping("/{id}/resolutions") public ResponseVO<?> resolve(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(service.resolve(id,input,request),request);}
}
