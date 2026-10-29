package com.yuewei.plm.common.util;

import com.yuewei.plm.common.constant.ErrorCodeConstants;
import com.yuewei.plm.common.exception.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ProductBusinessCodeGenerator {
    public static final String MOLD_PREFIX = "M";
    public static final String PRODUCT_STATE_PREFIX = "N";
    public static final String DEFAULT_FINAL_OPERATION_CODE = "4030";
    public static final String TPU_MATERIAL_CODE = "10";
    public static final String PC_MATERIAL_CODE = "20";
    public static final String PRODUCT_STATE_CODE_REGEX = "N([A-Z]{2})[0-9]{4}([0-9]{4})[A-Z0-9]{2}";
    /** Product prefix (MBA) + two-digit material (10) + four-digit phone (1199). */
    public static final String COMPLETE_MOLD_CODE_REGEX = "M[A-Z]{2}[0-9]{6}";
    private static final Pattern COMPLETE_MOLD_CODE_PATTERN = Pattern.compile(COMPLETE_MOLD_CODE_REGEX);
    private static final Pattern COMPLETE_MOLD_CODES_IN_TEXT = Pattern.compile("(?i)(?<![A-Z0-9_-])" + COMPLETE_MOLD_CODE_REGEX + "(?![A-Z0-9_-])");
    // Five-character source prefixes remain readable as catalogue evidence, without a phone identity.
    private static final Pattern IMPORTED_MOLD_CODE_PATTERN = Pattern.compile("([A-Z]{3})([0-9]{2})([0-9]{4})?");
    private static final Pattern MOLD_CODE_PATTERN = Pattern.compile("(?i)(?<![A-Z0-9_-])[A-Z]{3}\\d{2,}(?![A-Z0-9_-])");

    public String generateMoldCode(String productSpecificCode, String materialCode, String phoneModelCode) {
        String product = requiredSegment(productSpecificCode, "[A-Z]{2}", "产品特定编码须为两位英文字母");
        String material = requiredSegment(materialCode, "[0-9]{2}", "材质编码须为两位数字");
        String phone = requiredSegment(phoneModelCode, "[0-9]{4}", "手机型号编码须为四位数字");
        return MOLD_PREFIX + product + material + phone;
    }

    public String generateProductStateCode(String productSpecificCode, String operationCode,
                                           String phoneModelCode, String colorCode) {
        String product = requiredSegment(productSpecificCode, "[A-Z]{2}", "产品特定编码须为两位英文字母");
        String operation = requiredSegment(operationCode, "[0-9]{4}", "工序编码须为四位数字");
        String phone = requiredSegment(phoneModelCode, "[0-9]{4}", "手机型号编码须为四位数字");
        String color = requiredSegment(colorCode, "[A-Z0-9]{2}", "颜色编码须为两位字母或数字");
        return PRODUCT_STATE_PREFIX + product + operation + phone + color;
    }

    public String generateProductLineCode(String productSpecificCode, String operationCode) {
        String product = requiredSegment(productSpecificCode, "[A-Z]{2}", "产品特定编码须为两位英文字母");
        String operation = requiredSegment(operationCode, "[0-9]{4}", "工序编码须为四位数字");
        return PRODUCT_STATE_PREFIX + product + operation;
    }

    public ProductStateCodeParts parseProductStateCode(String productStateCode) {
        String code = requiredCode(productStateCode, "产品状态编码不能为空");
        if (!code.startsWith(PRODUCT_STATE_PREFIX)) {
            throw validation("产品状态编码必须以 N 开头");
        }
        String body = code.substring(PRODUCT_STATE_PREFIX.length());
        if (!code.matches(PRODUCT_STATE_CODE_REGEX)) {
            throw validation("产品状态编码长度或字段格式不正确: " + code);
        }
        String colorCode = body.substring(body.length() - 2);
        String phoneModelCode = body.substring(body.length() - 6, body.length() - 2);
        String operationCode = body.substring(body.length() - 10, body.length() - 6);
        String productSpecificCode = body.substring(0, body.length() - 10);
        if (!StringUtils.hasText(productSpecificCode)) {
            throw validation("产品状态编码缺少产品特定编码: " + code);
        }
        String expected = generateProductStateCode(productSpecificCode, operationCode, phoneModelCode, colorCode);
        if (!Objects.equals(expected, code)) {
            throw validation("产品状态编码与系统规则不一致: " + code);
        }
        return new ProductStateCodeParts(
            code,
            productSpecificCode,
            operationCode,
            phoneModelCode,
            colorCode,
            DEFAULT_FINAL_OPERATION_CODE.equals(operationCode),
            expected
        );
    }

    public boolean isFinishedProductCode(String code) {
        String normalized = requiredCode(code, "工序编码不能为空");
        if (normalized.startsWith(PRODUCT_STATE_PREFIX)) {
            return parseProductStateCode(normalized).finishedProduct();
        }
        return DEFAULT_FINAL_OPERATION_CODE.equals(normalized);
    }

    public List<String> splitCodes(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        Set<String> codes = new LinkedHashSet<>();
        value = value
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\\r", "\n")
            .replace("\\t", "\t");
        Matcher matcher = MOLD_CODE_PATTERN.matcher(value);
        while (matcher.find()) {
            String code = normalizeCode(matcher.group());
            if (StringUtils.hasText(code)) {
                codes.add(code);
            }
        }
        return new ArrayList<>(codes);
    }

    public static boolean isCompleteMoldCode(String value) {
        return value != null && COMPLETE_MOLD_CODE_PATTERN.matcher(value.trim().toUpperCase(Locale.ROOT)).matches();
    }

    public static String moldPartType(String materialCode) {
        if (TPU_MATERIAL_CODE.equals(materialCode)) return "TPU";
        if (PC_MATERIAL_CODE.equals(materialCode)) return "PC";
        return null;
    }

    public static boolean isMoldCodeForPart(String value, String partType) {
        return isCompleteMoldCode(value) && partType != null
            && partType.equals(moldPartType(value.trim().substring(3, 5)));
    }

    /** Complete executable mold codes; catalogue prefixes never identify a phone or physical mold. */
    public static List<String> splitCompleteMoldCodes(String value) {
        if (!StringUtils.hasText(value)) return List.of();
        Matcher matcher = COMPLETE_MOLD_CODES_IN_TEXT.matcher(value.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t"));
        Set<String> codes = new LinkedHashSet<>();
        while (matcher.find()) codes.add(matcher.group().toUpperCase(Locale.ROOT));
        return List.copyOf(codes);
    }

    public MoldCodeParts parseMoldCode(String moldCode, String expectedProductSpecificCode,
                                       List<String> expectedMaterialCodes, String expectedPhoneModelCode) {
        String code = requiredCode(moldCode, "模具编码不能为空");
        Matcher parsed = IMPORTED_MOLD_CODE_PATTERN.matcher(code);
        if (!parsed.matches()) throw validation("模具编码须为三位产品编码＋两位材质码＋四位手机型号码；历史资料仅可保留五位前缀: " + code);
        String productSpecificCode = parsed.group(1).substring(1);
        if (StringUtils.hasText(expectedProductSpecificCode)
            && !Objects.equals(productSpecificCode, normalizeCode(expectedProductSpecificCode))) {
            throw validation("模具编码产品特定编码与钉钉字段不一致: " + code);
        }
        String phoneModelCode = parsed.group(3);
        if (StringUtils.hasText(expectedPhoneModelCode)
            && !Objects.equals(phoneModelCode, requiredSegment(expectedPhoneModelCode, "[0-9]{4}", "手机型号编码须为四位数字"))) {
            throw validation("模具编码手机型号编码与业务字段不一致: " + code);
        }
        String materialCode = parsed.group(2);
        if (expectedMaterialCodes != null && !expectedMaterialCodes.isEmpty()
            && expectedMaterialCodes.stream().map(this::normalizeCode).noneMatch(materialCode::equals)) {
            throw validation("模具编码材质编码不在业务传入材质范围内: " + code);
        }
        return new MoldCodeParts(code, productSpecificCode, materialCode, phoneModelCode, code);
    }

    private String requiredSegment(String value, String pattern, String message) {
        String normalized = normalizeCode(value);
        if (normalized == null || !normalized.matches(pattern)) throw validation(message);
        return normalized;
    }

    private String requiredCode(String value, String message) {
        String normalized = normalizeCode(value);
        if (!StringUtils.hasText(normalized)) {
            throw validation(message);
        }
        if (!normalized.matches("[A-Z0-9_-]+")) {
            throw validation(message.replace("不能为空", "格式错误"));
        }
        return normalized;
    }

    private String normalizeCode(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, message);
    }

    public record MoldCodeParts(String moldCode, String productSpecificCode, String materialCode,
                                String phoneModelCode, String expectedMoldCode) {}

    public record ProductStateCodeParts(String productStateCode, String productSpecificCode, String operationCode,
                                        String phoneModelCode, String colorCode, boolean finishedProduct,
                                        String expectedProductStateCode) {}
}
