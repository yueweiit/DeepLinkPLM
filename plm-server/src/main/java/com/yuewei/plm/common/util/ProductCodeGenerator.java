package com.yuewei.plm.common.util;

import java.util.Locale;
import java.sql.SQLException;
import com.yuewei.plm.common.constant.ErrorCodeConstants;
import com.yuewei.plm.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductCodeGenerator {
    private final JdbcTemplate jdbc;

    public String generate(String productName) {
        String shortCode = productName == null || productName.isBlank()
            ? "PLM"
            : productName.replaceAll("[^A-Za-z0-9\\u4e00-\\u9fa5]", "")
                .toUpperCase(Locale.ROOT);
        String prefix = shortCode.length() > 6 ? shortCode.substring(0, 6) : shortCode;
        while (true) {
            long number;
            try {
                number = jdbc.queryForObject("select nextval('plm_product_code_seq')", Long.class);
            } catch (DataAccessException ex) {
                for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                    if (cause instanceof SQLException sql && "2200H".equals(sql.getSQLState())) {
                        throw new BusinessException(ErrorCodeConstants.CODE_CONFLICT, "项目自动编号已用尽，请联系管理员核对编号范围");
                    }
                }
                throw ex;
            }
            String candidate = "PRD-" + prefix + "-" + String.format(Locale.ROOT, "%04d", number);
            // Imported and manually supplied codes retain their identities, including deleted rows.
            if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from plm_product where product_code=?)", Boolean.class, candidate))) {
                return candidate;
            }
        }
    }
}
