package com.yuewei.plm.module.code.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuewei.plm.common.constant.ErrorCodeConstants;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.code.entity.CodeItem;
import com.yuewei.plm.module.code.repository.CodeItemRepository;
import com.yuewei.plm.module.code.vo.CodeImportErrorVO;
import com.yuewei.plm.module.code.vo.CodeImportPreviewVO;
import com.yuewei.plm.module.code.vo.CodeImportRowVO;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class CodeItemImportService {
    private static final List<ImportTemplate> TEMPLATES = List.of(
        new ImportTemplate("Códigos de color",
            List.of("Código color", "Nombre color", "Estado", "Actualizado"), false),
        new ImportTemplate("颜色编码",
            List.of("颜色编码", "颜色名称", "状态", "更新时间"), true)
    );
    private final CodeItemRepository repository;
    @org.springframework.beans.factory.annotation.Autowired private CodeLifecycleService lifecycle;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final Map<String, PreviewBatch> previews = new ConcurrentHashMap<>();

    public CodeImportPreviewVO preview(String fileName, byte[] content) {
        lifecycle.maintenance();
        if (!StringUtils.hasText(fileName) || !fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw validation("仅支持 xlsx 文件");
        }
        List<CodeImportRowVO> rows = new ArrayList<>();
        List<CodeImportErrorVO> errors = new ArrayList<>();
        Map<String, CodeItem> existing = new HashMap<>();
        List<CodeItem> existingRows = repository.selectList(new LambdaQueryWrapper<CodeItem>()
            .eq(CodeItem::getCodeType, "color"));
        if (existingRows != null) existingRows.forEach(item -> existing.put(item.getCodeValue(), item));
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            DataFormatter formatter = new DataFormatter();
            ImportTemplate template = findTemplate(workbook, formatter);
            if (template == null) throw validation("缺少颜色编码工作表或第 6 行表头不匹配");
            var sheet = workbook.getSheet(template.sheetName());
            Set<String> seen = new HashSet<>();
            int sortOrder = 1;
            for (int index = 6; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null) continue;
                String code = formatter.formatCellValue(row.getCell(0)).trim();
                String sourceName = formatter.formatCellValue(row.getCell(1)).trim();
                String sourceStatus = formatter.formatCellValue(row.getCell(2)).trim();
                if (code.isBlank() && sourceName.isBlank() && sourceStatus.isBlank()) continue;
                if (code.isBlank()) { errors.add(error(index + 1, code, "编码", "颜色编码不能为空")); continue; }
                if (sourceName.isBlank()) {
                    errors.add(error(index + 1, code, template.chineseName() ? "中文名称" : "名称",
                        "颜色名称不能为空"));
                    continue;
                }
                if (!seen.add(code)) { errors.add(error(index + 1, code, "编码", "同一文件内颜色编码重复")); continue; }
                String status;
                try { status = mapStatus(sourceStatus); }
                catch (BusinessException exception) { errors.add(error(index + 1, code, "状态", exception.getMessage())); continue; }
                CodeItem current = existing.get(code);
                if(current!=null&&(Integer.valueOf(1).equals(current.getDeletedFlag())||!"enabled".equals(current.getStatus()))){errors.add(error(index+1,code,"编码","该编码已删除或停用，请在原档案维护；导入不能复活"));continue;}
                String codeName = template.chineseName()
                    ? currentName(current, sourceName) : sourceName;
                String codeNameZh = template.chineseName()
                    ? sourceName : current == null ? null : current.getCodeNameZh();
                String action = current == null ? "create"
                    : same(current, codeName, codeNameZh, status, sortOrder) ? "unchanged" : "update";
                rows.add(CodeImportRowVO.builder().rowNo(index + 1).codeValue(code).codeName(codeName)
                    .codeNameZh(codeNameZh)
                    .status(status).sortOrder(sortOrder).action(action).build());
                sortOrder++;
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw validation("XLSX 解析失败：" + exception.getMessage());
        }
        String token = UUID.randomUUID().toString();
        previews.put(token, new PreviewBatch(LocalDateTime.now().plusHours(2), rows, errors));
        return result(token, rows, errors, 0);
    }

    @Transactional
    public CodeImportPreviewVO commit(String token) {
        lifecycle.maintenance();
        PreviewBatch batch = previews.remove(token);
        if (batch == null || batch.expiresAt().isBefore(LocalDateTime.now())) {
            throw validation("导入令牌不存在、已过期或已提交");
        }
        if (!batch.errors().isEmpty()) throw validation("导入预览存在错误，不能提交");
        int committed = 0;
        for (CodeImportRowVO row : batch.rows()) {
            if ("unchanged".equals(row.getAction())) continue;
            CodeItem existing = repository.selectOne(new LambdaQueryWrapper<CodeItem>()
                .eq(CodeItem::getCodeType, "color").eq(CodeItem::getCodeValue, row.getCodeValue())
                );
            if(existing!=null&&(Integer.valueOf(1).equals(existing.getDeletedFlag())||!"enabled".equals(existing.getStatus())))throw validation("编码已删除或停用，请重新预览");
            if (existing == null) {
                existing = new CodeItem();
                existing.setCodeType("color"); existing.setCodeValue(row.getCodeValue());
                fillCreate(existing);
            }
            if(existing.getCodeItemId()!=null){jdbc.queryForList("select code_item_id from plm_code_item where code_item_id=? for update",existing.getCodeItemId());existing.setVersion(java.util.Objects.requireNonNullElse(existing.getVersion(),0L)+1);}
            Object before=existing.getCodeItemId()==null?null:Map.of("name",existing.getCodeName(),"status",existing.getStatus(),"version",java.util.Objects.requireNonNullElse(existing.getVersion(),0L));
            existing.setCodeName(row.getCodeName()); existing.setCodeNameZh(row.getCodeNameZh());
            existing.setStatus(row.getStatus());
            existing.setSortOrder(row.getSortOrder()); touch(existing);
            if (existing.getCodeItemId() == null) repository.insert(existing); else repository.updateById(existing);
            lifecycle.saved(existing.getCodeItemId(),before,existing,"颜色编码文件导入");
            committed++;
        }
        return result(token, batch.rows(), batch.errors(), committed);
    }

    private ImportTemplate findTemplate(XSSFWorkbook workbook, DataFormatter formatter) {
        for (ImportTemplate template : TEMPLATES) {
            var sheet = workbook.getSheet(template.sheetName());
            if (sheet != null && validHeader(sheet.getRow(5), formatter, template.headers())) return template;
        }
        return null;
    }

    private boolean validHeader(Row row, DataFormatter formatter, List<String> headers) {
        if (row == null) return false;
        List<String> actual = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            actual.add(formatter.formatCellValue(row.getCell(index)).trim());
        }
        return headers.equals(actual);
    }

    private String currentName(CodeItem current, String fallback) {
        return current == null || !StringUtils.hasText(current.getCodeName())
            ? fallback : current.getCodeName();
    }

    private String mapStatus(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "enabled", "启用" -> "enabled";
            case "disabled", "停用", "禁用" -> "disabled";
            default -> throw validation("未知颜色状态：" + value);
        };
    }

    private boolean same(CodeItem item, String name, String nameZh, String status, int sortOrder) {
        return name.equals(item.getCodeName())
            && java.util.Objects.equals(nameZh, item.getCodeNameZh())
            && status.equals(item.getStatus())
            && Integer.valueOf(sortOrder).equals(item.getSortOrder());
    }

    private CodeImportPreviewVO result(String token, List<CodeImportRowVO> rows,
        List<CodeImportErrorVO> errors, int committed) {
        return CodeImportPreviewVO.builder().importToken(token)
            .createCount((int) rows.stream().filter(row -> "create".equals(row.getAction())).count())
            .updateCount((int) rows.stream().filter(row -> "update".equals(row.getAction())).count())
            .unchangedCount((int) rows.stream().filter(row -> "unchanged".equals(row.getAction())).count())
            .errorCount(errors.size()).committedCount(committed).rows(rows).errors(errors).build();
    }

    private CodeImportErrorVO error(int row, String code, String field, String reason) {
        return new CodeImportErrorVO(row, code, field, reason);
    }

    private void fillCreate(CodeItem item) {
        LocalDateTime now = LocalDateTime.now();
        item.setVersion(0L); item.setCreatedAt(now); item.setCreatedBy(lifecycle.actorName()); item.setDeletedFlag(0);
    }

    private void touch(CodeItem item) {
        item.setUpdatedAt(LocalDateTime.now()); item.setUpdatedBy(lifecycle.actorName());
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCodeConstants.VALIDATION_ERROR, message);
    }

    private record PreviewBatch(LocalDateTime expiresAt, List<CodeImportRowVO> rows,
                                List<CodeImportErrorVO> errors) {
    }

    private record ImportTemplate(String sheetName, List<String> headers, boolean chineseName) {
    }
}
