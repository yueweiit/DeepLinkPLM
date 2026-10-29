package com.yuewei.plm.module.moldapplication;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MoldApplicationSourceSnapshotsTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void repeatedAndDisappearingSourcesKeepTheirOriginalEvidenceWhileNewSourcesAppend()throws Exception {
        var first=json.readTree("{\"capturedAt\":\"first\",\"phoneModelId\":12,\"sources\":[{\"kind\":\"candidate\",\"id\":1,\"model\":\"IP 18 Pro\",\"price_raw\":\"first-price\"}]}");
        var second=json.readTree("{\"capturedAt\":\"second\",\"phoneModelId\":12,\"sources\":[{\"kind\":\"candidate\",\"id\":1,\"model\":\"Changed source\"},{\"kind\":\"phone\",\"id\":2,\"model\":\"iPhone 18 Pro\"}]}");
        var merged=MoldApplicationSourceSnapshots.merge(first,second,json);assertThat(merged.path("capturedAt").asText()).isEqualTo("first");assertThat(merged.path("sources").size()).isEqualTo(2);assertThat(merged.path("sources").get(0).path("model").asText()).isEqualTo("IP 18 Pro");
        assertThat(MoldApplicationSourceSnapshots.merge(merged,second,json)).isEqualTo(merged);assertThat(MoldApplicationSourceSnapshots.merge(merged,json.readTree("{\"sources\":[]}"),json)).isEqualTo(merged);
        assertThat(first.path("sources").size()).isEqualTo(1);
    }
}
