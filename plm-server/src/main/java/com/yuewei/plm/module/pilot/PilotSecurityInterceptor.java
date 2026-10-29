package com.yuewei.plm.module.pilot;
import com.yuewei.plm.common.exception.BusinessException;import com.yuewei.plm.common.security.CurrentUserContext;
import jakarta.servlet.http.*;import java.util.*;import java.util.regex.Pattern;import lombok.RequiredArgsConstructor;import org.springframework.stereotype.Component;import org.springframework.web.servlet.HandlerInterceptor;

/** Central table-driven compatibility boundary. Restricted pilot users fail closed. */
@Component @RequiredArgsConstructor
public class PilotSecurityInterceptor implements HandlerInterceptor {
 private final PilotAccessService access;
 private record ObjectRule(Pattern path,String type,String permission){}
 private static final List<ObjectRule> OBJECTS=List.of(
  new ObjectRule(Pattern.compile("/api/v1/attachments/(\\d+)(?:/.*)?"),"file","file:read"),
  new ObjectRule(Pattern.compile("/api/v1/molds/(\\d+)(?:/.*)?"),"mold","collaboration:view"),
  new ObjectRule(Pattern.compile("/api/v1/collaboration-plans/items/(\\d+)(?:/.*)?"),"item","collaboration:view"),
  new ObjectRule(Pattern.compile("/api/v1/boms/(\\d+)(?:/.*)?"),"bom","product:view"),
  new ObjectRule(Pattern.compile("/api/v1/process-routes/(\\d+)(?:/.*)?"),"process","process:view"),
  new ObjectRule(Pattern.compile("/api/v1/(?:products|projects)/(\\d+)(?:/.*)?"),"product","project:view"));
 private static final Map<String,String> COLLECTIONS=Map.ofEntries(
  Map.entry("/api/v1/dashboard","dashboard:view"),Map.entry("/api/v1/projects","project:view"),Map.entry("/api/v1/products","product:view"),
  Map.entry("/api/v1/bom","product:view"),Map.entry("/api/v1/process","process:view"),Map.entry("/api/v1/inventories","inventory:view"),
  Map.entry("/api/v1/suppliers","supplier:view"),Map.entry("/api/v1/orders","order:view"),Map.entry("/api/v1/production-orders","production-order:view"),
  Map.entry("/api/v1/collaboration-plans","collaboration:view"),Map.entry("/api/v1/molds","collaboration:view"),Map.entry("/api/v1/code-items","product:view"),
  Map.entry("/api/v1/file-center","file:read"),Map.entry("/api/v1/reports","report:view"));
 private static final List<String> SAFE=List.of("/api/v1/auth/profile","/api/v1/auth/logout","/api/v1/pilot/me");
 @Override public boolean preHandle(HttpServletRequest r,HttpServletResponse response,Object handler){
  if(CurrentUserContext.get().isEmpty())return true;
  var a=access.access();CurrentUserContext.set(a.actor());if(a.actor().allPermissions())return true;
  String path=r.getRequestURI();boolean read=Set.of("GET","HEAD").contains(r.getMethod());
  if(path.contains("cost-summary")||path.contains("cost-items")||path.contains("/costs/")||path.endsWith("/costs")||path.startsWith("/api/v1/cost"))access.requirePermission(financeAction(path,read));
  // Master data is a global dictionary for authorized domestic readers. Writes always
  // require the existing engineering responsibility, including non-pilot accounts.
  if(path.equals("/api/v1/code-items")||path.startsWith("/api/v1/code-items/")){
   access.requireDomesticView();
   if(read){if(!access.hasPermission("product:view"))access.requirePermission("collaboration:view");}
   else access.requireEngineeringMaintenance();
   return true;
  }
  if((path.equals("/api/v1/inventories")||path.startsWith("/api/v1/inventories/"))&&!path.startsWith("/api/v1/inventories/suppliers")){
   access.requireDomesticView();access.requirePermission("inventory:view");
   if(!read)access.requireEngineeringMaintenance();
   return true;
  }
  if(path.equals("/api/v1/products/master-data")||path.startsWith("/api/v1/products/master-data/")){
   access.requireDomesticView();access.requirePermission("product:view");
   if(!read)access.requireEngineeringMaintenance();
   return true;
  }
  if(path.matches("/api/v1/products/\\d+/master-data-(?:references|actions/[^/]+)")){
   access.requireDomesticView();access.requirePermission("product:view");
   if(!read)access.requireEngineeringMaintenance();
   return true; // Service enforces object scope and permits an identical tombstone retry.
  }
  if(path.matches("/api/v1/molds/\\d+/(?:references|actions/[^/]+)")){
   access.requireDomesticView();access.requirePermission("collaboration:view");
   if(!read)access.requireEngineeringMaintenance();
   return true;
  }
  if(!read&&(path.equals("/api/v1/products")||path.matches("/api/v1/products/\\d+(?:/basic-info)?"))){
   access.requireDomesticView();access.requirePermission("documents:write");
   if(!path.equals("/api/v1/products"))access.requireProduct(Long.parseLong(path.split("/")[4]));
   return true;
  }
  if(samplePath(path))return sampleBoundary(path,r.getMethod(),a.role());
  if(!a.pilot())return true; // Historical non-financial workflows retain their behavior.
  if(SAFE.contains(path))return true;
  if(path.startsWith("/api/v1/master-data/")){access.requireDomesticView();if(path.equals("/api/v1/master-data/status")&&read){if(!access.hasPermission("product:view"))access.requirePermission("collaboration:view");}else access.requireAdministrator();return true;}
  if(path.equals("/api/v1/products/current-catalog")||path.startsWith("/api/v1/products/current-catalog/")){access.requireDomesticView();if(!read)throw denied();if(!access.hasPermission("product:view"))access.requirePermission("collaboration:view");return true;}
  if(read&&path.matches("/api/v1/process-routes/\\d+/graph")){access.requireDomesticView();access.requirePermission("process:view");return true;}
  if(read&&path.equals("/api/v1/process-routes/current")){access.requireDomesticView();access.requirePermission("process:view");return true;}
  if(path.startsWith("/api/v1/partner-products")||path.startsWith("/api/v1/partner-sales")||path.startsWith("/api/v1/samples")||path.startsWith("/api/v1/partner-options")||path.equals("/api/v1/partner-selections")||path.startsWith("/api/v1/partner-selections/")){access.requireDomesticView();access.requirePermission("partner:view");return true;}
  if(path.equals("/api/v1/partner-catalog")||path.startsWith("/api/v1/partner-catalog/")){access.requireDomesticView();access.requirePermission("partner:view");return true;}
  if(r.getMethod().equals("GET")&&path.equals("/api/v1/market-watch/config")){access.requireDomesticView();access.requirePermission("collaboration:view");return true;}
  if(path.startsWith("/api/v1/partner-operations/")){access.requireDomesticView();access.requirePermission("partner:view");return true;}
  if(path.startsWith("/api/v1/customers")){access.requireDomesticView();access.requirePermission("customer:view");return true;}
  if(path.startsWith("/api/v1/inventories/suppliers")){access.requireDomesticView();access.requirePermission(read?"supplier:view":"partner:write");return true;}
  if(path.equals("/api/v1/product-research")||path.startsWith("/api/v1/product-research/")){access.requireDomesticView();access.requirePermission(read?"project:view":"documents:write");return true;}
  if(path.equals("/api/v1/mold-sales")||path.startsWith("/api/v1/mold-sales/")){access.requireDomesticView();access.requirePermission("collaboration:view");return true;}
  if(path.startsWith("/api/v1/mold-applications")){access.requirePermission("collaboration:view");return true;}
  if(path.startsWith("/api/v1/file-center/documents")||Set.of("/api/v1/file-center/reference-options","/api/v1/file-center/reference-options/page","/api/v1/file-center/reference-options/phones").contains(path)||path.equals("/api/v1/file-center/project-nodes")){access.requireDomesticView();access.requirePermission(read?"file:read":"documents:write");return true;}
  if(path.startsWith("/api/v1/pilot/tasks")){access.requirePermission("pilot:tasks");return true;}
  if(path.startsWith("/api/v1/pilot/")||path.startsWith("/api/v1/system/")){access.requireAdministrator();return true;}
  if(access.isExternal()){
   for(var rule:OBJECTS){var m=rule.path().matcher(path);if(m.matches()&&rule.type().equals("file")&&read){access.requireFile(Long.parseLong(m.group(1)));return true;}}
   throw denied();
  }
  if(path.startsWith("/api/v1/pilot-delivery/")){
   if(path.startsWith("/api/v1/pilot-delivery/tasks")||!read){access.requireAdministrator();return true;}
   access.requirePermission(path.contains("export")?"finance:export":"report:view");return true;
  }
  if(path.startsWith("/api/v1/development")){access.requirePermission(financeAction(path,read));return true;}
  if(path.startsWith("/api/v1/collaboration-plans/schedules")){access.requirePermission(path.endsWith("/remind")?"schedule:remind":read?"schedule:read":"schedule:write");return true;}
  if(read&&Set.of("/api/v1/molds/reference-data/phone-models","/api/v1/molds/reference-data/phone-models/options").contains(path)){access.requirePhoneSelectionRead();return true;}
  // Master detail and reference services enforce their own field and object boundaries.
  if(path.startsWith("/api/v1/molds/reference-data/")||path.startsWith("/api/v1/molds/masters")){
   access.requireDomesticView();access.requirePermission("collaboration:view");return true;
  }
  // These collections now enforce their object scope inside the shared service/repository.
  if(read&&Set.of("/api/v1/collaboration-plans","/api/v1/collaboration-plans/options","/api/v1/collaboration-plans/project-options","/api/v1/collaboration-plans/mold-code-options","/api/v1/molds","/api/v1/molds/options").contains(path)
    ||!read&&Set.of("/api/v1/collaboration-plans/items","/api/v1/collaboration-plans/batch-save","/api/v1/molds","/api/v1/molds/batch-save").contains(path)){
   access.requireDomesticView();access.requirePermission("collaboration:view");return true;
  }
  if(path.matches(".*(?:export|templates|download-xlsx).*"))access.requireFinanceExport();
  for(var rule:OBJECTS){var m=rule.path().matcher(path);if(m.matches()){
   access.requirePermission(rule.permission());long id=Long.parseLong(m.group(1));switch(rule.type()){case "file"->access.requireFile(id);case "mold"->access.requireMold(id);case "item"->access.requireItem(id);case "product"->access.requireProduct(id);case "bom"->access.requireBom(id);case "process"->access.requireProcess(id);default->throw denied();}
   if(rule.type().equals("file")&&!read)access.requirePermission(path.endsWith("/release")?"documents:publish":"documents:write");
   if(path.contains("/boms")&&!read)access.requirePermission(path.endsWith("/publish")?"bom:publish":"bom:write");
   if(!read&&!a.role().equals("domestic")&&!path.contains("cost-items"))throw denied();return true;}}
  // Old collection endpoints cannot apply object scopes; only explicitly unrestricted domestic users may use them.
  if(a.allObjects()&&a.role().equals("domestic"))for(var entry:COLLECTIONS.entrySet())if(path.startsWith(entry.getKey())){access.requirePermission(entry.getValue());if(path.startsWith("/api/v1/boms")&&!read)access.requirePermission(path.endsWith("/publish")?"bom:publish":"bom:write");return true;}
  throw denied();
 }
 /** These new routes have scoped services; admit only their reviewed methods and paths. */
 private static boolean samplePath(String path){return path.equals("/api/v1/sample-identities")||path.startsWith("/api/v1/sample-identities/")||path.equals("/api/v1/sample-shipments")||path.startsWith("/api/v1/sample-shipments/")||path.equals("/api/v1/sample-scan")||path.startsWith("/api/v1/sample-scan/");}
 private boolean sampleBoundary(String path,String method,String role){
  boolean get=method.equals("GET"),post=method.equals("POST");
  if(role.equals("mexico")){
   boolean assignedRead=get&&(path.equals("/api/v1/sample-scan")||path.equals("/api/v1/sample-shipments")||path.matches("/api/v1/sample-shipments/[0-9]+"));
   boolean assignedReceipt=post&&path.matches("/api/v1/sample-shipments/[0-9]+/actions/receive");
   if(!assignedRead&&!assignedReceipt)throw denied();access.requirePermission("task:arrival");return true; // Service still checks recipient, actual dispatch and row allocation.
  }
  if(Set.of("supplier","overseas_sales").contains(role))throw denied();access.requireDomesticView();
  boolean identityRead=get&&(path.equals("/api/v1/sample-identities")||path.matches("/api/v1/sample-identities/(?:[0-9]+|resolve|candidates)"));
  boolean identityWrite=post&&(path.equals("/api/v1/sample-identities")||path.matches("/api/v1/sample-identities/[0-9]+/actions/(?:loan|return|move|retain|gift|consume|scrap|loss|count|undo|custody)")||path.matches("/api/v1/sample-identities/batches/[0-9]+/identity"));
  boolean shipmentRead=get&&(path.equals("/api/v1/sample-shipments")||path.matches("/api/v1/sample-shipments/(?:[0-9]+|options)"));
  boolean shipmentWrite=post&&(path.equals("/api/v1/sample-shipments")||path.matches("/api/v1/sample-shipments/[0-9]+/(?:files|actions/(?:dispatched|tracking|receive|loss|cancel|undo))"))||Set.of("PUT","DELETE").contains(method)&&path.matches("/api/v1/sample-shipments/[0-9]+");
  if(identityRead||shipmentRead||get&&path.equals("/api/v1/sample-scan")){access.requirePermission("partner:view");return true;}
  if(identityWrite||shipmentWrite){access.requirePermission("samples:write");return true;}
  throw denied();
 }
 private static String financeAction(String path,boolean read){return path.contains("export")?"finance:export":path.endsWith("/confirm")?"finance:confirm":read?"finance:read":"finance:write";}
 private static BusinessException denied(){return new BusinessException(40103,"试点账号无权使用该入口，请使用已分配的任务或授权视图");}
}
