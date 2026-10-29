package com.yuewei.plm.module.inventory.controller;
import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import com.yuewei.plm.module.inventory.maintenance.InventoryMaintenanceService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/inventories")
public class InventoryMaintenanceController {
 private final InventoryMaintenanceService service;
 private ResponseVO<?> ok(Object v,HttpServletRequest r){return ResponseVO.success(v,RequestIdUtil.getRequestId(r),OffsetDateTime.now());}
 @GetMapping("/{id:[0-9]+}") public ResponseVO<?> detail(@PathVariable long id,HttpServletRequest r){return ok(service.detail(id,false),r);}
 @PostMapping public ResponseVO<?> create(@RequestBody Map<String,Object>d,HttpServletRequest r){return ok(service.save(null,false,d),r);}
 @PutMapping("/{id:[0-9]+}") public ResponseVO<?> update(@PathVariable long id,@RequestBody Map<String,Object>d,HttpServletRequest r){return ok(service.save(id,false,d),r);}
 @GetMapping("/{id:[0-9]+}/references") public ResponseVO<?> refs(@PathVariable long id,HttpServletRequest r){return ok(service.inspect(id,false),r);}
 @PostMapping("/{id:[0-9]+}/actions/{action}") public ResponseVO<?> action(@PathVariable long id,@PathVariable String action,@RequestBody Map<String,Object>d,HttpServletRequest r){return ok(service.lifecycle(id,false,action,d),r);}
 @GetMapping("/groups/maintenance") public ResponseVO<?> groups(HttpServletRequest r){return ok(service.groups(),r);}
 @GetMapping("/groups/{id:[0-9]+}") public ResponseVO<?> group(@PathVariable long id,HttpServletRequest r){return ok(service.detail(id,true),r);}
 @PostMapping("/groups") public ResponseVO<?> createGroup(@RequestBody Map<String,Object>d,HttpServletRequest r){return ok(service.save(null,true,d),r);}
 @PutMapping("/groups/{id:[0-9]+}") public ResponseVO<?> updateGroup(@PathVariable long id,@RequestBody Map<String,Object>d,HttpServletRequest r){return ok(service.save(id,true,d),r);}
 @GetMapping("/groups/{id:[0-9]+}/references") public ResponseVO<?> groupRefs(@PathVariable long id,HttpServletRequest r){return ok(service.inspect(id,true),r);}
 @PostMapping("/groups/{id:[0-9]+}/actions/{action}") public ResponseVO<?> groupAction(@PathVariable long id,@PathVariable String action,@RequestBody Map<String,Object>d,HttpServletRequest r){return ok(service.lifecycle(id,true,action,d),r);}
}
