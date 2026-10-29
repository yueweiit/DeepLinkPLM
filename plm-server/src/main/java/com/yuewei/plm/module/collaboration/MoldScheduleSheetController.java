package com.yuewei.plm.module.collaboration;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;

@RestController @RequiredArgsConstructor @org.springframework.validation.annotation.Validated
@RequestMapping({"/api/v1/mold-schedules/sheet-sync","/api/v1/collaboration-plans/schedules/sheet-sync"})
public class MoldScheduleSheetController {
    private final MoldScheduleSheetSync service;
    private ResponseVO<?> ok(Object result,HttpServletRequest request){return ResponseVO.success(result,RequestIdUtil.getRequestId(request),OffsetDateTime.now());}
    @GetMapping public ResponseVO<?> status(HttpServletRequest r){return ok(service.status(),r);}
    @PostMapping public ResponseVO<?> update(HttpServletRequest r){return ok(service.start(),r);}
    @PutMapping("/settings") public ResponseVO<?> settings(@jakarta.validation.Valid @RequestBody MoldScheduleSheetSync.Settings input,HttpServletRequest r){return ok(service.settings(input),r);}
    @GetMapping("/fields/{id}") public ResponseVO<?> fields(@PathVariable @jakarta.validation.constraints.Positive long id,HttpServletRequest r){return ok(service.fields(id),r);}
    @PostMapping("/fields/{id}/resume") public ResponseVO<?> resume(@PathVariable @jakarta.validation.constraints.Positive long id,@jakarta.validation.Valid @RequestBody MoldScheduleSheetSync.Resume input,HttpServletRequest r){return ok(service.resume(id,input),r);}
    @PostMapping("/bindings") public ResponseVO<?> bind(@jakarta.validation.Valid @RequestBody MoldScheduleSheetSync.Binding input,HttpServletRequest r){return ok(service.bind(input),r);}
}
