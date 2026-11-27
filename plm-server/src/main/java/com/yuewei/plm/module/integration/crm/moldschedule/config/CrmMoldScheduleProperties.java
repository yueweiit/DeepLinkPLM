package com.yuewei.plm.module.integration.crm.moldschedule.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "plm.integrations.crm.mold-schedule")
public class CrmMoldScheduleProperties {
    private boolean enabled;
    private String endpointUrl = "";
    private String path = "/api/integrations/v1/plm/mold-schedules/events";
    private String keyId = "";
    private String secret = "";
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 15000;
    private int pollIntervalMs = 5000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEndpointUrl() { return endpointUrl; }
    public void setEndpointUrl(String endpointUrl) { this.endpointUrl = endpointUrl == null ? "" : endpointUrl.trim(); }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path == null ? "" : path.trim(); }
    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId == null ? "" : keyId.trim(); }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret == null ? "" : secret; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public int getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(int pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

    public boolean isConfigured() {
        if (endpointUrl.isBlank() || keyId.isBlank() || secret.isBlank() || path.isBlank()) return false;
        try {
            URI uri = URI.create(endpointUrl);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                && (uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))
                && keyId.matches("[A-Za-z0-9._-]{1,64}")
                && path.startsWith("/") && !path.startsWith("//") && !path.contains("?") && !path.contains("#")
                && !path.contains("\r") && !path.contains("\n")
                && connectTimeoutMs > 0 && readTimeoutMs > 0 && pollIntervalMs > 0;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public String targetUrl() {
        if (!isConfigured()) throw new IllegalStateException("CRM mold schedule integration is not configured");
        return endpointUrl.replaceAll("/+$", "") + path;
    }
}
