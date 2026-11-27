package com.yuewei.plm.module.integration.crm.productlisting;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix="plm.integrations.crm.product-listing")
public class CrmProductListingProperties {
    private boolean enabled = false;
    private int maxAttempts = 5;
}
