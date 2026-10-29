package com.yuewei.plm.module.partner;

import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Engineering records supplier costs; this never grants customer finance or payment authority. */
@Component @RequiredArgsConstructor
public class PartnerOperationsAccess {
 public static final Set<String> KINDS=Set.of("supplier_quote","sample_fee","inspection","fulfilment","listing");
 private final PilotAccessService access;
 public boolean engineering(){return access.canMaintainEngineeringData()&&access.hasPermission("partner:write");}
 public boolean costs(){return engineering()||access.hasPermission("finance:read");}
 public void view(String kind){access.requireDomesticView();access.requirePermission("partner:view");require(KINDS.contains(kind),"业务类型无效");if(Set.of("supplier_quote","sample_fee").contains(kind))require(costs(),"仅工程或获授权财务可查看供方报价与样品费用");}
 public void engineer(){access.requireDomesticView();access.requirePermission("partner:write");access.requireEngineeringMaintenance();}
 public void sales(String action){access.requireDomesticView();access.requirePermission("partner:view");access.requirePermission(action.equals("listing")?"partner:listing":"partner:delivery");}
 public void write(String kind){view(kind);if(kind.equals("listing"))sales("listing");else engineer();}
}
