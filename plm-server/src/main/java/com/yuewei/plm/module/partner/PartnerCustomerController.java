package com.yuewei.plm.module.partner;
import com.yuewei.plm.common.vo.ResponseVO;
import com.yuewei.plm.common.util.RequestIdUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/customers")
public class PartnerCustomerController {
 private final PartnerCustomerService service;
 private ResponseVO<?> ok(Object value,HttpServletRequest r){return ResponseVO.success(value,RequestIdUtil.getRequestId(r),OffsetDateTime.now());}
 @GetMapping public ResponseVO<?> list(@RequestParam Map<String,String> q,HttpServletRequest r){return ok(q.containsKey("page")?service.page(q):service.all(),r);}
 @GetMapping("/{id}") public ResponseVO<?> detail(@PathVariable long id,HttpServletRequest r){return ok(service.detail(id),r);}
 @PostMapping public ResponseVO<?> create(@RequestBody Map<String,Object> d,HttpServletRequest r){return ok(service.save(null,d),r);}
 @PutMapping("/{id}") public ResponseVO<?> update(@PathVariable long id,@RequestBody Map<String,Object> d,HttpServletRequest r){return ok(service.save(id,d),r);}
 @GetMapping("/{id}/references") public ResponseVO<?> references(@PathVariable long id,HttpServletRequest r){return ok(service.references(id),r);}
 @PostMapping("/{id}/actions/{action}") public ResponseVO<?> action(@PathVariable long id,@PathVariable String action,@RequestBody Map<String,Object>d,HttpServletRequest r){return ok(service.lifecycle(id,action,d),r);}
}
