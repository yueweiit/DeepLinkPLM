package com.yuewei.plm.module.partner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class SupplierShortCodesTest {
    @Test void shortCodesRemainReadableAndHaveNoThreeOrThirtyTwoCharacterLimit() {
        assertThat(SupplierShortCodes.change(" xd2 ","")).isEqualTo("XD2");
        assertThat(SupplierShortCodes.change("supplier-"+"A".repeat(40),"")).isEqualTo("SUPPLIER-"+"A".repeat(40));
        assertThat(SupplierShortCodes.change("X","")).isEqualTo("X");
        assertThat(SupplierShortCodes.change("","")).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"熊大","XD_2","XD 2","ＸＤ","-XD","XD／2","XD–2"})
    void newCodesCannotContainChineseOrUnicodeLookalikes(String value) {
        assertThatThrownBy(()->SupplierShortCodes.change(value,"")).hasMessageContaining("半角横线");
    }
    @Test void unchangedLegacyCodesRemainVerbatim() {
        assertThat(SupplierShortCodes.change("old_code"," old_code ")).isEqualTo(" old_code ");
        assertThat(SupplierShortCodes.change("旧号","旧号")).isEqualTo("旧号");
        assertThat(SupplierShortCodes.change("ABC","aBc")).isEqualTo("aBc");
    }
    @Test void confirmedXdBindingCannotBeChangedOrCleared() {
        assertThat(SupplierShortCodes.change(" xd ","XD")).isEqualTo("XD");
        assertThatThrownBy(()->SupplierShortCodes.change("XD2","XD")).hasMessageContaining("不能清空");
        assertThatThrownBy(()->SupplierShortCodes.change("","xd")).hasMessageContaining("不能清空");
    }
}
