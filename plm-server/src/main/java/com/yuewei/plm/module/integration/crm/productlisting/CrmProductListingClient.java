package com.yuewei.plm.module.integration.crm.productlisting;

import java.util.Map;

/** Implement only after CRM confirms authentication, image delivery and idempotent upsert semantics. */
public interface CrmProductListingClient {
    boolean ready();
    String upsertProduct(Map<String,Object> product, String idempotencyKey);
    String upsertSku(String crmProductId, Map<String,Object> sku, String idempotencyKey);

    /** Adapter must translate remote failures to these safe codes, never expose raw response bodies. */
    class DeliveryException extends RuntimeException {
        private final boolean retryable;
        public DeliveryException(boolean retryable) { super("CRM_DELIVERY_REJECTED"); this.retryable=retryable; }
        public boolean retryable() { return retryable; }
    }
}
