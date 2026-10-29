package com.yuewei.plm.module.moldapplication;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data @Component @ConfigurationProperties(prefix="app.mold-application")
public class MoldApplicationProperties {
    private boolean enabled;
    private boolean readSyncEnabled;
    private boolean writeEnabled;
    private boolean centralCodeEnabled;
    private String processCode="PROC-89949932-19DE-48EB-A20B-2595CB0F804D";
    private String connectorToken="";
    private String callbackSignatureToken="";
    private String callbackAesKey="";
    private String callbackOwnerKey="";
    private List<Long> allowedSubmitters=new ArrayList<>();
    private String importDirectory="";
    private String backupRoot="";
    private int syncIntervalMs=900000;
}
