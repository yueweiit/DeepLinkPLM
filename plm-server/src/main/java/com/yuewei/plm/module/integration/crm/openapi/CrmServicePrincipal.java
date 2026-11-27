package com.yuewei.plm.module.integration.crm.openapi;

import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;

public record CrmServicePrincipal(String subjectId,long userId,Set<String> scopes) {
    public static final String ATTRIBUTE=CrmServicePrincipal.class.getName();
    public CrmServicePrincipal {scopes=Set.copyOf(scopes);}
    public void require(String scope){if(!scopes.contains(scope))throw new CrmApiException(403,"FORBIDDEN","服务凭证没有所需权限");}
    public static CrmServicePrincipal from(HttpServletRequest request){
        if(request.getAttribute(ATTRIBUTE) instanceof CrmServicePrincipal value)return value;
        throw new CrmApiException(401,"UNAUTHENTICATED","缺少有效 CRM 服务凭证");
    }
}
