package com.yuewei.plm.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yuewei.plm.common.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductBusinessCodeGeneratorTest {
    private final ProductBusinessCodeGenerator generator = new ProductBusinessCodeGenerator();

    @Test
    void generatesMoldAndProductStateCodes() {
        assertThat(generator.generateMoldCode("FA", "10", "1291")).isEqualTo("MFA101291");
        assertThat(generator.generateProductLineCode("FA", "4020")).isEqualTo("NFA4020");
        assertThat(generator.generateProductStateCode("FA", "4030", "1291", "01")).isEqualTo("NFA4030129101");
        assertThat(generator.isFinishedProductCode("4030")).isTrue();
    }

    @Test
    void parsesProductStateCodeAndDetectsFinishedProductByFinalOperation() {
        ProductBusinessCodeGenerator.ProductStateCodeParts parts = generator.parseProductStateCode("NFA4030129101");

        assertThat(parts.productSpecificCode()).isEqualTo("FA");
        assertThat(parts.operationCode()).isEqualTo("4030");
        assertThat(parts.phoneModelCode()).isEqualTo("1291");
        assertThat(parts.colorCode()).isEqualTo("01");
        assertThat(parts.finishedProduct()).isTrue();
        assertThat(generator.isFinishedProductCode("NFA4030129101")).isTrue();
        assertThat(generator.isFinishedProductCode("NFA1020129101")).isFalse();
    }

    @Test
    void parsesIncomingMoldCodeAgainstExpectedFields() {
        ProductBusinessCodeGenerator.MoldCodeParts parts = generator.parseMoldCode(
            "MFA101291",
            "FA",
            List.of("10", "20"),
            "1291"
        );

        assertThat(parts.productSpecificCode()).isEqualTo("FA");
        assertThat(parts.materialCode()).isEqualTo("10");
        assertThat(parts.phoneModelCode()).isEqualTo("1291");
        assertThat(parts.expectedMoldCode()).isEqualTo("MFA101291");
    }

    @Test
    void parsesShortMoldCodeBySecondAndThirdCharacters() {
        ProductBusinessCodeGenerator.MoldCodeParts mba = generator.parseMoldCode("MBA10", "BA", List.of("10"), null);
        ProductBusinessCodeGenerator.MoldCodeParts llt = generator.parseMoldCode("LLT10", "LT", List.of("10"), null);

        assertThat(mba.moldCode()).isEqualTo("MBA10");
        assertThat(mba.productSpecificCode()).isEqualTo("BA");
        assertThat(mba.materialCode()).isEqualTo("10");
        assertThat(mba.expectedMoldCode()).isEqualTo("MBA10");
        assertThat(llt.moldCode()).isEqualTo("LLT10");
        assertThat(llt.productSpecificCode()).isEqualTo("LT");
        assertThat(llt.materialCode()).isEqualTo("10");
    }

    @Test
    void splitsEscapedNewlineMoldCodesFromDingTalkConnector() {
        assertThat(generator.splitCodes("MFA101291\\nMFA201291\\nLLT10"))
            .containsExactly("MFA101291", "MFA201291", "LLT10");
    }

    @Test
    void extractsMoldCodesFromDingTalkFormattedText() {
        assertThat(generator.splitCodes("生成的编码：MBA10、LLT10 / MFA301291"))
            .containsExactly("MBA10", "LLT10", "MFA301291");
    }

    @Test
    void rejectsMoldCodeWhenPhoneModelCodeDoesNotMatch() {
        assertThatThrownBy(() -> generator.parseMoldCode("MFA101292", "FA", List.of("10"), "1291"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("手机型号编码");
    }

    @Test
    void userConfirmedMoldCodeAndSkuShareTheSameProductAndPhoneIdentity() {
        var mold = generator.parseMoldCode("MBA101199", "BA", List.of("10", "20"), "1199");
        var sku = generator.parseProductStateCode("NBA4030119902");
        assertThat(mold.materialCode()).isEqualTo("10");
        assertThat(mold.productSpecificCode()).isEqualTo(sku.productSpecificCode()).isEqualTo("BA");
        assertThat(mold.phoneModelCode()).isEqualTo(sku.phoneModelCode()).isEqualTo("1199");
        assertThat(generator.generateMoldCode("BA", "10", "1199")).isEqualTo("MBA101199");
    }

    @Test
    void preservesLeadingZerosAndNeverPadsOrUsesATrailingPartialPhoneCode() {
        assertThat(generator.generateMoldCode("ba", "10", "0185")).isEqualTo("MBA100185");
        assertThat(generator.parseMoldCode(" mba100185 ", null, List.of(), null).phoneModelCode()).isEqualTo("0185");
        assertThatThrownBy(() -> generator.generateMoldCode("BA", "10", "185")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> generator.parseMoldCode("MBA101199", "BA", List.of("10"), "199"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("四位");
        assertThatThrownBy(() -> generator.parseMoldCode("MBA101199", "BA", List.of("1"), "1199"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("材质");
        assertThatThrownBy(() -> generator.parseMoldCode("MBA10", "BA", List.of("10"), "1199"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("手机型号");
    }

    @ParameterizedTest
    @ValueSource(strings = {"MBA10119", "MBA1011991", "MBA1A1199", "MBA10119A", "MBA_101199"})
    void malformedSegmentsCannotBecomeAnExecutableMoldIdentity(String code) {
        assertThat(ProductBusinessCodeGenerator.isCompleteMoldCode(code)).isFalse();
        assertThatThrownBy(() -> generator.parseMoldCode(code, null, List.of(), null)).isInstanceOf(BusinessException.class);
    }

    @Test
    void completeMoldExtractionSeparatesCataloguePrefixesFromExecutableCodes() {
        assertThat(ProductBusinessCodeGenerator.splitCompleteMoldCodes("MBA10 / mba101199\\nMBA201199 / MBA101199 / XMBA101199 / MBA1011991 / LLT10"))
            .containsExactly("MBA101199", "MBA201199");
        assertThat(generator.parseMoldCode("MBA10", null, List.of(), null).phoneModelCode()).isNull();
    }

    @Test
    void malformedGenerationInputsCannotShiftOtherCodeSegments() {
        assertThatThrownBy(() -> generator.generateMoldCode("MBA", "10", "1199")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> generator.generateMoldCode("BA", "1", "1199")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> generator.generateProductStateCode("BA", "4030", "119", "02")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> generator.generateProductStateCode("BA", "403", "1199", "02")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> generator.parseProductStateCode("NBAA4030119902")).isInstanceOf(BusinessException.class);
    }
}
