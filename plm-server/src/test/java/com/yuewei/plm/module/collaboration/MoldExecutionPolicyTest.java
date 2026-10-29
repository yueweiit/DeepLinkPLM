package com.yuewei.plm.module.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MoldExecutionPolicyTest {
    private final ObjectMapper json=new ObjectMapper();
    private final LocalDate today=LocalDate.of(2026,10,14);
    private void change(String key,Object value,String reason) {
        MoldExecutionPolicy.validate(Map.of(),Map.of(key,value),List.of(new CollaborationTypes.CellChange(key,json.nullNode(),json.valueToTree(value),reason)),today);
    }
    @Test void pauseBlocksFutureExecutionEvenWithAnExplanation() {
        assertThatThrownBy(()->change("plannedMoldDeliveryDate","2026-10-30","重新安排")).hasMessageContaining("不能推进");
        assertThatThrownBy(()->change("forecastFactoryDate","2026-10-30","重新安排")).hasMessageContaining("不能推进");
        assertThatThrownBy(()->change("actualShipDate","2026-10-30","预计发模")).hasMessageContaining("已经发生");
    }
    @Test void pastFactsNeedAnExplicitReasonAndDoNotRequireClearingSavedHistory() {
        assertThatThrownBy(()->change("actualShipDate","2026-10-13",null)).hasMessageContaining("核对原因");
        assertThatCode(()->change("actualShipDate","2026-10-13","补录暂停前已发模事实")).doesNotThrowAnyException();
        assertThatCode(()->change("actualSupplierTrialDate","2026-10-12","补录原试模单")).doesNotThrowAnyException();
        assertThatThrownBy(()->change("pcAcceptanceStatus","passed",null)).hasMessageContaining("核对原因");
    }
    @Test void descriptiveNotesRemainEditableDuringPause() {
        assertThatCode(()->change("scheduleNotes","等待工程核对；原日期保留",null)).doesNotThrowAnyException();
    }
}
