package com.yuewei.plm.module.partner;
import java.util.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;
/** Warehouse access never grants domestic source or partner-record access. */
final class SampleSiteAccess {
 private SampleSiteAccess(){}
 static boolean mexico(PartnerStore store){var access=store.access.access();return access!=null&&"mexico".equals(access.role());}
 static String read(PartnerStore store,Object rawSite){return access(store,rawSite,false);}
 static String write(PartnerStore store,Object rawSite){return access(store,rawSite,true);}
 private static String access(PartnerStore store,Object rawSite,boolean write){String site=text(rawSite).toUpperCase(Locale.ROOT);require(site.isEmpty()||Set.of("CN","MX").contains(site),"请选择中国或墨西哥站点");if(mexico(store)){store.access.requirePermission("task:arrival");require(site.isEmpty()||site.equals("MX"),"墨西哥库位操作仅限墨西哥站点");return "MX";}store.access.requireDomesticView();store.access.requirePermission(write?"samples:write":"partner:view");return site;}
}
