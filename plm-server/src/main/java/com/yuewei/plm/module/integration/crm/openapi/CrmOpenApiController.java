package com.yuewei.plm.module.integration.crm.openapi;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping(CrmOpenApiSecurityConfig.PREFIX)
public class CrmOpenApiController {
    private final CrmQueryService query;private final CrmScheduleDateService dates;private final CrmSyncStore sync;
    public CrmOpenApiController(CrmQueryService query,CrmScheduleDateService dates,CrmSyncStore sync){this.query=query;this.dates=dates;this.sync=sync;}
    @GetMapping("/mold-schedules")
    public ResponseEntity<?> molds(@RequestParam Map<String,String> args,HttpServletRequest r){CrmServicePrincipal.from(r).require("crm:mold-schedule:read");return response(query.page("MOLD_SET",args));}
    @GetMapping("/mold-schedules/{id}")
    public ResponseEntity<?> mold(@PathVariable String id,HttpServletRequest r){CrmServicePrincipal.from(r).require("crm:mold-schedule:read");return response(query.detail("MOLD_SET",CrmQueryService.positiveId(id)));}
    @PatchMapping("/mold-schedules/{id}/crm-dates")
    public ResponseEntity<?> save(@PathVariable String id,@RequestHeader(value="If-Match",required=false)String version,@RequestHeader(value="Idempotency-Key",required=false)String key,@RequestBody String body,HttpServletRequest r){return response(dates.save(CrmQueryService.positiveId(id),version,key,body,r));}
    @GetMapping("/phone-models")
    public ResponseEntity<?> phones(@RequestParam Map<String,String> args,HttpServletRequest r){CrmServicePrincipal.from(r).require("crm:phone-model:read");return response(query.page("PHONE_MODEL",args));}
    @GetMapping("/phone-models/{id}")
    public ResponseEntity<?> phone(@PathVariable String id,HttpServletRequest r){CrmServicePrincipal.from(r).require("crm:phone-model:read");return response(query.detail("PHONE_MODEL",CrmQueryService.positiveId(id)));}
    @GetMapping("/changes")
    public ResponseEntity<?> changes(@RequestParam Map<String,String> args,HttpServletRequest r){
        var p=CrmServicePrincipal.from(r);p.require("crm:mold-schedule:read");p.require("crm:phone-model:read");
        if(!java.util.Set.of("cursor","pageSize").containsAll(args.keySet()))throw CrmApiException.invalid("存在不支持的查询参数");
        return response(sync.changes(args.get("cursor"),CrmQueryService.integer(args.getOrDefault("pageSize","100"),1,100)));
    }
    private static ResponseEntity<?> response(Map<String,Object> value){var builder=ResponseEntity.ok().header("Cache-Control","no-store");if(value.containsKey("recordVersion"))builder.eTag(value.get("recordVersion").toString());return builder.body(Map.of("data",value));}
}
