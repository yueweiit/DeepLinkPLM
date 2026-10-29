package com.yuewei.plm.module.pilot;

import com.yuewei.plm.module.partner.*;
import java.util.Set;

/** Exact engineering supplier endpoints; never grants access to customer finance controllers. */
final class PilotSupplierCostPolicy {
 private PilotSupplierCostPolicy(){}
 private static final Set<Class<?>> CONTROLLERS=Set.of(PartnerOperationsController.class,PartnerSelectionController.class,PartnerSelectionImportController.class,PartnerQuoteLinesController.class,PartnerCatalogController.class);
 static boolean engineeringController(Class<?> type,PilotAccessService access){return CONTROLLERS.contains(type)&&access.hasPermission("partner:write")&&access.canMaintainEngineeringData();}
}
