package com.yuewei.plm.module.moldapplication;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class MoldCallbackCryptoTest {
    final String key=Base64.getEncoder().withoutPadding().encodeToString(new byte[32]);
    @Test void signedEnterpriseCallbackRoundTripsUnicodeAndAcknowledgesSuccess(){var crypto=new MoldCallbackCrypto("test-token",key,"test-corp");var encrypted=crypto.encrypt("{\"model\":\"开模测试\"}");assertThat(crypto.decrypt(encrypted.get("msg_signature"),encrypted.get("timeStamp"),encrypted.get("nonce"),encrypted.get("encrypt"))).isEqualTo("{\"model\":\"开模测试\"}");}
    @Test void forgedSignatureWrongOwnerAndStaleTimestampAreRejected(){var crypto=new MoldCallbackCrypto("test-token",key,"test-corp");var e=crypto.encrypt("success");assertThatThrownBy(()->crypto.decrypt("bad",e.get("timeStamp"),e.get("nonce"),e.get("encrypt"))).hasMessageContaining("验证失败");var other=new MoldCallbackCrypto("test-token",key,"another-corp");assertThatThrownBy(()->other.decrypt(e.get("msg_signature"),e.get("timeStamp"),e.get("nonce"),e.get("encrypt"))).hasMessageContaining("验证失败");assertThatThrownBy(()->crypto.decrypt(e.get("msg_signature"),"0",e.get("nonce"),e.get("encrypt"))).hasMessageContaining("验证失败");}
    @Test void unsetCallbackCredentialsNeverAllowAnonymousCallbacks(){assertThatThrownBy(()->new MoldCallbackCrypto("",key,"test-corp")).hasMessageContaining("验证失败");assertThatThrownBy(()->new MoldCallbackCrypto("token","invalid","test-corp")).hasMessageContaining("验证失败");}
    @Test void millisecondTimestampDoesNotNeedToBeAnExactSecond()throws Exception {var crypto=new MoldCallbackCrypto("test-token",key,"test-corp");var e=crypto.encrypt("success");String timestamp=Long.toString(java.time.Instant.now().getEpochSecond()*1000+123);assertThat(crypto.decrypt(crypto.signature(timestamp,e.get("nonce"),e.get("encrypt")),timestamp,e.get("nonce"),e.get("encrypt"))).isEqualTo("success");}
}
