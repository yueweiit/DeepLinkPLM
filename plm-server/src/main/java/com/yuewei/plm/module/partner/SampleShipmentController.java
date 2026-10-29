package com.yuewei.plm.module.partner;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static com.yuewei.plm.module.partner.PartnerValues.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/sample-shipments")
public class SampleShipmentController {
 private final SampleShipmentService service;private final PartnerMaterialService materials;
 private ResponseVO<?> ok(Object data,HttpServletRequest r){return ResponseVO.success(data,RequestIdUtil.getRequestId(r),OffsetDateTime.now());}
 @GetMapping public Object page(@RequestParam Map<String,String> query,HttpServletRequest r){return ok(service.page(query),r);}
 @GetMapping("/options") public Object options(HttpServletRequest r){return ok(service.options(),r);}
 @PostMapping public Object create(@RequestBody Map<String,Object> input,HttpServletRequest r){return ok(service.create(input),r);}
 @GetMapping("/{id}") public Object detail(@PathVariable long id,HttpServletRequest r){return ok(service.detail(id),r);}
 @PutMapping("/{id}") public Object save(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest r){return ok(service.save(id,input),r);}
 @DeleteMapping("/{id}") public Object delete(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest r){return ok(service.delete(id,input),r);}
 @PostMapping("/{id}/actions/{action}") public Object action(@PathVariable long id,@PathVariable String action,@RequestBody Map<String,Object> input,HttpServletRequest r){return ok(service.action(id,action,input),r);}
 @PostMapping("/{id}/files") public Object file(@PathVariable long id,@RequestParam MultipartFile file,@RequestParam(defaultValue="material") String purpose,@RequestParam(required=false) Long previous,@RequestParam(defaultValue="V1") String version,@RequestParam(defaultValue="") String reason,HttpServletRequest r){service.requireFileWrite(id);require(Set.of("photo","material").contains(purpose),"寄样仅上传物流或实收凭证");return ok(materials.upload(id,"sample_shipment",file,purpose,previous,version,reason,r),r);}
}
