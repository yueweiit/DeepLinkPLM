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

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/partner-operations")
public class PartnerOperationsController {
 private final PartnerOperationsService service;
 private final PartnerOperationsAccess access;
 private final PartnerMaterialService materials;
 private final PartnerListingService listings;
 private final PartnerStore store;
 private final PartnerSpecBrowser specs;
 private ResponseVO<?> ok(Object data,HttpServletRequest r){return ResponseVO.success(data,RequestIdUtil.getRequestId(r),OffsetDateTime.now());}
 @GetMapping("/capabilities") public ResponseVO<?> capabilities(HttpServletRequest r){return ok(service.capabilities(),r);}
 @GetMapping("/spec-options") public ResponseVO<?> specOptions(@RequestParam Map<String,String> q,HttpServletRequest r){return ok(specs.page(q),r);}
 @GetMapping("/spec-options/phones") public ResponseVO<?> specPhones(@RequestParam Map<String,String> q,HttpServletRequest r){return ok(specs.phones(q),r);}
 @GetMapping("/{kind}") public ResponseVO<?> page(@PathVariable String kind,@RequestParam Map<String,String> q,HttpServletRequest r){return ok(service.page(kind,q),r);}
 @GetMapping("/{kind}/{id}") public ResponseVO<?> detail(@PathVariable String kind,@PathVariable long id,HttpServletRequest r){return ok(service.detail(kind,id),r);}
 @PostMapping("/{kind}") public ResponseVO<?> create(@PathVariable String kind,@RequestBody Map<String,Object> d,HttpServletRequest r){return ok(service.create(kind,d),r);}
 @PostMapping("/{kind}/{id}/actions/{action}") public ResponseVO<?> action(@PathVariable String kind,@PathVariable long id,@PathVariable String action,@RequestBody Map<String,Object> d,HttpServletRequest r){return ok(service.action(kind,id,action,d),r);}
 @PostMapping("/products/{id}/listing-confirmations") public ResponseVO<?> listing(@PathVariable long id,@RequestBody Map<String,Object> d,HttpServletRequest r){return ok(listings.confirm(id,d),r);}
 @GetMapping("/catalog/listing-options") public ResponseVO<?> catalogListingOptions(@RequestParam long selectionId,@RequestParam String selectionRowId,HttpServletRequest r){return ok(listings.catalogOptions(selectionId,selectionRowId),r);}
 @PostMapping("/catalog/listing-confirmations") public ResponseVO<?> catalogListing(@RequestBody Map<String,Object> d,HttpServletRequest r){return ok(listings.confirmCatalog(d),r);}
 @GetMapping("/catalog/listings") public ResponseVO<?> catalogListings(@RequestParam Map<String,String> q,HttpServletRequest r){return ok(listings.catalogPage(q),r);}
 @PostMapping("/{kind}/{id}/files") public ResponseVO<?> file(@PathVariable String kind,@PathVariable long id,@RequestParam MultipartFile file,@RequestParam(defaultValue="material") String purpose,HttpServletRequest r){
  if(kind.equals("fulfilment")&&!access.engineering())access.sales("delivery");else access.write(kind);require(!kind.equals("listing"),"上架依据引用冻结产品资料，不另上传替换");if(Set.of("supplier_quote","sample_fee").contains(kind))purpose="cooperation_cost";
  if(kind.equals("inspection"))require(Set.of("photo","material","factory_signature").contains(purpose),"检验仅记录本次质量资料，不混入费用凭据");
  return ok(materials.upload(id,kind,file,purpose,null,"V1","本次实际依据",r),r);
 }
 @GetMapping("/tasks") public ResponseVO<?> tasks(HttpServletRequest r){
  store.access.requireDomesticView();store.access.requirePermission("partner:view");var rows=store.jdbc.queryForList("select record_id as id,kind,code,name,status,data->>'reason' as reason from plm_partner_record r where kind in ('inspection','fulfilment','listing') and status in ('draft','pending','pending_signature','not_connected','accepted','completed','shipping') and deleted_flag=0 and "+store.access.visibleSqlClause("partner","r.record_id")+" and (owner_user_id=? or ? or (? and kind='listing')) order by updated_at desc,record_id desc limit 100",store.access.actorId(),access.engineering(),store.access.hasPermission("partner:listing"));return ok(rows,r);
 }
}
