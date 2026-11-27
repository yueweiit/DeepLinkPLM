package com.yuewei.plm.module.integration.crm.moldschedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuewei.plm.module.integration.crm.moldschedule.service.CrmHmacSigner;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class CrmHmacSignerTest {
    private final CrmHmacSigner signer = new CrmHmacSigner();

    @Test
    void signsTheExactDocumentedCanonicalRequestAndRawBodyDigest() throws Exception {
        byte[] body = "abc".getBytes(StandardCharsets.UTF_8);
        String bodyHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        String keyId = "test-key";
        String timestamp = "1793993400";
        String eventId = "event-1";
        String canonical = String.join("\n", "POST", "/api/integrations/v1/plm/mold-schedules/events",
            keyId, timestamp, eventId, bodyHash);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));

        assertThat(signer.bodySha256(body)).isEqualTo(bodyHash);
        assertThat(signer.signature("POST", "/api/integrations/v1/plm/mold-schedules/events", keyId,
            timestamp, eventId, body, "secret")).isEqualTo(expected).matches("[0-9a-f]{64}");
        assertThat(signer.signature("POST", "/api/integrations/v1/plm/mold-schedules/events", keyId,
            timestamp, eventId, "abd".getBytes(StandardCharsets.UTF_8), "secret")).isNotEqualTo(expected);
    }
}
