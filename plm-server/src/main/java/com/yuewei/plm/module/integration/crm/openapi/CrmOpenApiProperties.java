package com.yuewei.plm.module.integration.crm.openapi;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data @Component @ConfigurationProperties(prefix="plm.integrations.crm.openapi")
public class CrmOpenApiProperties {
    private boolean enabled=false;
    private boolean writeEnabled=false;
    private String subjectId="crm";
    private long serviceUserId;
    private List<String> tokenDigests=List.of();
    private String signingSecret="";
    private List<String> scopes=List.of("crm:mold-schedule:read","crm:phone-model:read");
    private int changeRetentionDays=30;
    private int idempotencyRetentionDays=7;
    public boolean configured(){return enabled&&serviceUserId>0&&subjectId!=null&&!subjectId.isBlank()
        &&signingSecret!=null&&signingSecret.length()>=32&&tokenDigests!=null&&!tokenDigests.isEmpty()
        &&tokenDigests.stream().allMatch(s->s!=null&&s.matches("[a-fA-F0-9]{64}"))
        &&scopes!=null&&!scopes.isEmpty()&&java.util.Set.of("crm:mold-schedule:read","crm:phone-model:read","crm:schedule-dates:write").containsAll(scopes)
        &&changeRetentionDays>=1&&idempotencyRetentionDays>=7;}
}
