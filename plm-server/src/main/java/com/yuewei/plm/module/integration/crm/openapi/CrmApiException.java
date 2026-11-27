package com.yuewei.plm.module.integration.crm.openapi;

public final class CrmApiException extends RuntimeException {
    private final int status;
    private final String code;
    public CrmApiException(int status,String code,String message) {super(message);this.status=status;this.code=code;}
    public int status(){return status;}
    public String code(){return code;}
    public static CrmApiException invalid(String message){return new CrmApiException(400,"INVALID_ARGUMENT",message);}
    public static CrmApiException unavailable(){return new CrmApiException(503,"TEMPORARILY_UNAVAILABLE","CRM 接口尚未启用或同步基础未就绪");}
}
