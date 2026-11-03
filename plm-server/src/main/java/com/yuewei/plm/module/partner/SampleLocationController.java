package com.yuewei.plm.module.partner;

import com.yuewei.plm.common.vo.ResponseVO;
import com.yuewei.plm.common.util.RequestIdUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/sample-locations")
public class SampleLocationController {
 private final SampleLocationService locations;
 private Object ok(Object data,HttpServletRequest request){return ResponseVO.success(data,RequestIdUtil.getRequestId(request),OffsetDateTime.now());}
 @GetMapping public Object page(@RequestParam Map<String,String> query,HttpServletRequest r){return ok(locations.page(query),r);}
 @GetMapping("/{id}") public Object detail(@PathVariable long id,HttpServletRequest r){return ok(locations.detail(id),r);}
 @GetMapping("/{id}/references") public Object references(@PathVariable long id,HttpServletRequest r){return ok(locations.references(id),r);}
 @GetMapping("/options") public Object options(@RequestParam Map<String,String> query,HttpServletRequest r){return ok(locations.options(query),r);}
 @PostMapping public Object create(@RequestBody Map<String,Object> input,HttpServletRequest r){return ok(locations.create(input),r);}
 @PutMapping("/{id}") public Object update(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest r){return ok(locations.update(id,input),r);}
 @DeleteMapping("/{id}") public Object delete(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest r){return ok(locations.delete(id,input),r);}
}
