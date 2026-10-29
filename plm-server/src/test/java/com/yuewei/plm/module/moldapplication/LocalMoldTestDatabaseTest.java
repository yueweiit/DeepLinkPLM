package com.yuewei.plm.module.moldapplication;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class LocalMoldTestDatabaseTest {
    @Test void permitsNewLoopbackQaPortsWithoutWhitelistingEveryPort() {
        assertThat(LocalMoldTestDatabase.database("jdbc:postgresql://127.0.0.1:15437/plm_data_management?currentSchema=plm,public")).isEqualTo("plm_data_management");
        assertThatCode(()->LocalMoldTestDatabase.verifyIdentity("plm_data_management","plm_data_management","7701886875467657256")).doesNotThrowAnyException();
    }
    @Test void refusesRemoteUnknownDatabaseAndProductionOrWrongIdentityBeforeWriting() {
        for(String url:new String[]{"jdbc:postgresql://production:15437/plm_data_management","jdbc:postgresql://127.0.0.1:15437/postgres","jdbc:postgresql://user@127.0.0.1:15437/plm_data_management"})
            assertThatThrownBy(()->LocalMoldTestDatabase.database(url)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->LocalMoldTestDatabase.verifyIdentity("plm_data_management","plm_data_management","7651923859845308451")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->LocalMoldTestDatabase.verifyIdentity("plm_data_management","postgres","7701886875467657256")).isInstanceOf(IllegalArgumentException.class);
    }
}
