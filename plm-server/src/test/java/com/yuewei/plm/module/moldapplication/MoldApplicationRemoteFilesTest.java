package com.yuewei.plm.module.moldapplication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.module.integration.dingtalk.service.DingTalkAccessTokenService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MoldApplicationRemoteFilesTest {
    @Test void onlyOfficialOssDownloadUrisMayBeUpgradedToHttpsAndNeverUsedAsDurablePhotos() {
        String address="http://lippi-space-zjk.oss-cn-zhangjiakou.aliyuncs.com/file?Signature=private";
        assertThat(MoldApplicationMedia.archiveUri(address,true).toString()).isEqualTo(address.replace("http:","https:"));
        assertThat(MoldApplicationDingTalkFiles.durablePhotoUrl(address.replace("http:","https:"))).isFalse();
        for(String rejected:new String[]{address,"https://127.0.0.1/private","https://user@static.dingtalk.com/a.png","https://static.dingtalk.com.evil.test/a.png","https://lippi-space-zjk.oss-cn-zhangjiakou.aliyuncs.com.evil.test/a","https://static.dingtalk.com:8000/a"})
            assertThatThrownBy(()->MoldApplicationMedia.archiveUri(rejected,false)).isInstanceOf(com.yuewei.plm.common.exception.BusinessException.class);
    }
    @Test void realPngBytesRemainAnImageWhenOssReturnsOctetStream() {
        byte[] png={-119,80,78,71,13,10,26,10,0};assertThat(MoldApplicationMedia.archivedContentType(png,"application/octet-stream")).isEqualTo("image/png");
        assertThat(MoldApplicationMedia.archivedContentType(new byte[]{1,2},"application/octet-stream")).isEqualTo("application/octet-stream");
    }
    @Test void downloadAuthorizationIsBoundToTheOriginalInstanceAndFileAndDoesNotSubmitAnything()throws Exception {
        var json=new ObjectMapper();var client=spy(new MoldDingTalkClient(mock(DingTalkAccessTokenService.class),json));
        doReturn(json.readTree("{\"success\":true,\"result\":{\"fileId\":\"file-original\",\"downloadUri\":\"http://lippi-space-zjk.oss-cn-zhangjiakou.aliyuncs.com/a?Signature=private\"}}")).when(client).modern(anyString(),any());
        assertThat(client.approvalFileDownloadUrl("instance-original","file-original")).contains("oss-cn-zhangjiakou.aliyuncs.com");
        verify(client).modern("/v1.0/workflow/processInstances/spaces/files/urls/download",Map.of("processInstanceId","instance-original","fileId","file-original"));verify(client,never()).submit(anyMap());
        doReturn(json.readTree("{\"success\":true,\"result\":{\"fileId\":\"another\",\"downloadUri\":\"https://static.dingtalk.com/a\"}}")).when(client).modern(anyString(),any());
        assertThatThrownBy(()->client.approvalFileDownloadUrl("instance-original","file-original")).hasMessageContaining("附件身份");
        doReturn(json.readTree("{\"success\":false,\"result\":{}}")).when(client).modern(anyString(),any());
        assertThatThrownBy(()->client.approvalFileDownloadUrl("instance-original","file-original")).hasMessageContaining("未授权");
    }
}
