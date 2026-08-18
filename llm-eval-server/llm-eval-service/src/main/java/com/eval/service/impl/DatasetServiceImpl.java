package com.eval.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eval.common.exception.BusinessException;
import com.eval.common.result.PageResult;
import com.eval.dao.mapper.*;
import com.eval.model.dto.DatasetItemDTO;
import com.eval.model.dto.SchemaFieldDTO;
import com.eval.model.entity.*;
import com.eval.model.vo.DatasetEvalHistoryVO;
import com.eval.service.DatasetService;
import com.eval.service.MinioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetServiceImpl implements DatasetService {

    private final EvalDatasetMapper datasetMapper;
    private final EvalDatasetItemMapper datasetItemMapper;
    private final EvalDatasetSchemaMapper schemaMapper;
    private final MinioService minioService;
    private final EvalTaskMapper taskMapper;
    private final EvalTaskSummaryMapper summaryMapper;
    private final EvalTaskModelMapper taskModelMapper;
    private final EvalTaskPromptMapper taskPromptMapper;
    private final EvalModelConfigMapper modelConfigMapper;
    private final EvalPromptMapper promptMapper;

    // ========== 字段名到角色的自动推荐映射 ==========
    private static final Map<String, String> ROLE_HINTS = new HashMap<>();
    static {
        ROLE_HINTS.put("question", "QUESTION"); ROLE_HINTS.put("问题", "QUESTION");
        ROLE_HINTS.put("query", "QUESTION"); ROLE_HINTS.put("input", "QUESTION");
        ROLE_HINTS.put("prompt", "QUESTION"); ROLE_HINTS.put("输入", "QUESTION");
        ROLE_HINTS.put("reference_answer", "REFERENCE"); ROLE_HINTS.put("参考答案", "REFERENCE");
        ROLE_HINTS.put("answer", "REFERENCE"); ROLE_HINTS.put("参考", "REFERENCE");
        ROLE_HINTS.put("回复", "REFERENCE"); ROLE_HINTS.put("response", "REFERENCE");
        ROLE_HINTS.put("context", "CONTEXT"); ROLE_HINTS.put("上下文", "CONTEXT");
        ROLE_HINTS.put("人设", "CONTEXT"); ROLE_HINTS.put("背景", "CONTEXT");
        ROLE_HINTS.put("background", "CONTEXT"); ROLE_HINTS.put("persona", "CONTEXT");
        ROLE_HINTS.put("category", "CATEGORY"); ROLE_HINTS.put("分类", "CATEGORY");
        ROLE_HINTS.put("话题", "CATEGORY"); ROLE_HINTS.put("topic", "CATEGORY");
        ROLE_HINTS.put("type", "CATEGORY"); ROLE_HINTS.put("类型", "CATEGORY");
    }

    @Override
    public Map<String, Object> previewFile(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        String fileType = detectFileType(originalFilename);
        try (InputStream is = file.getInputStream()) {
            List<String> columns = new ArrayList<>();
            List<Map<String, String>> previewRows = new ArrayList<>();

            if ("XLSX".equals(fileType)) {
                Workbook wb = new XSSFWorkbook(is);
                Sheet sheet = wb.getSheetAt(0);
                if (sheet.getPhysicalNumberOfRows() == 0) {
                    wb.close();
                    throw new BusinessException("文件无数据");
                }
                // 第一行为列名
                Row headerRow = sheet.getRow(0);
                for (Cell cell : headerRow) {
                    columns.add(getCellStringValue(cell));
                }
                // 最多预览5行
                int maxRows = Math.min(sheet.getPhysicalNumberOfRows(), 6);
                for (int i = 1; i < maxRows; i++) {
                    Row row = sheet.getRow(i);
                    if (row == null) continue;
                    Map<String, String> rowData = new LinkedHashMap<>();
                    for (int j = 0; j < columns.size(); j++) {
                        Cell cell = j < row.getPhysicalNumberOfCells() ? row.getCell(j) : null;
                        rowData.put(columns.get(j), cell != null ? getCellStringValue(cell) : "");
                    }
                    previewRows.add(rowData);
                }
                wb.close();
            } else if ("JSON".equals(fileType)) {
                String content = new String(is.readAllBytes(), "UTF-8");
                List<Object> rawList = JSONUtil.toList(content, Object.class);
                if (rawList.isEmpty()) throw new BusinessException("JSON文件无数据");
                // 从第一条数据取列名
                if (rawList.get(0) instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> first = (Map<String, Object>) rawList.get(0);
                    columns.addAll(first.keySet());
                    int max = Math.min(rawList.size(), 5);
                    for (int i = 0; i < max; i++) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) rawList.get(i);
                        Map<String, String> rowData = new LinkedHashMap<>();
                        for (String col : columns) {
                            rowData.put(col, m.get(col) != null ? String.valueOf(m.get(col)) : "");
                        }
                        previewRows.add(rowData);
                    }
                }
            } else {
                // CSV
                String content = new String(is.readAllBytes(), "UTF-8");
                String[] lines = content.split("\n");
                if (lines.length == 0) throw new BusinessException("CSV文件无数据");
                String[] headers = lines[0].trim().split(",", -1);
                for (String h : headers) columns.add(h.trim());
                int max = Math.min(lines.length, 6);
                for (int i = 1; i < max; i++) {
                    String line = lines[i].trim();
                    if (StrUtil.isBlank(line)) continue;
                    String[] parts = line.split(",", -1);
                    Map<String, String> rowData = new LinkedHashMap<>();
                    for (int j = 0; j < columns.size(); j++) {
                        rowData.put(columns.get(j), j < parts.length ? parts[j].trim() : "");
                    }
                    previewRows.add(rowData);
                }
            }

            // 自动推荐角色映射
            List<Map<String, Object>> suggestedMapping = new ArrayList<>();
            for (int i = 0; i < columns.size(); i++) {
                String col = columns.get(i);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("fieldName", col);
                m.put("displayName", col);
                m.put("fieldType", "TEXT");
                m.put("role", guessRole(col));
                m.put("description", "");
                m.put("required", "QUESTION".equals(guessRole(col)) ? 1 : 0);
                m.put("sortOrder", i);
                suggestedMapping.add(m);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("columns", columns);
            result.put("previewRows", previewRows);
            result.put("suggestedMapping", suggestedMapping);
            result.put("fileType", fileType);
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("预览文件失败", e);
            throw new BusinessException("预览文件失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public EvalDataset upload(MultipartFile file, String name, String description, Integer hasReference,
                             Integer hasModelResponse,
                             List<SchemaFieldDTO> schemaFields, String columnMapping) {
        String originalFilename = file.getOriginalFilename();
        String fileType = detectFileType(originalFilename);

        // 存储到 MinIO
        String filePath = minioService.uploadFile(file);

        // 解析文件内容
        List<EvalDatasetItem> items = parseFile(file, fileType, schemaFields);

        // 创建数据集记录
        EvalDataset dataset = new EvalDataset();
        dataset.setName(name);
        EvalDataset latest = datasetMapper.selectOne(
                new LambdaQueryWrapper<EvalDataset>()
                        .eq(EvalDataset::getName, name)
                        .orderByDesc(EvalDataset::getVersion)
                        .last("LIMIT 1"));
        dataset.setVersion(latest != null ? latest.getVersion() + 1 : 1);
        dataset.setDescription(description != null ? description : "");
        dataset.setFilePath(filePath);
        dataset.setFileType(fileType);
        dataset.setColumnMapping(columnMapping);
        dataset.setHasReference(hasReference != null ? hasReference : 1);
        dataset.setHasModelResponse(hasModelResponse != null ? hasModelResponse : 0);
        dataset.setTotalCount(items.size());
        dataset.setCreatedAt(LocalDateTime.now());
        datasetMapper.insert(dataset);

        // 保存Schema
        if (schemaFields != null) {
            for (int i = 0; i < schemaFields.size(); i++) {
                SchemaFieldDTO dto = schemaFields.get(i);
                EvalDatasetSchema schema = new EvalDatasetSchema();
                schema.setDatasetId(dataset.getId());
                schema.setFieldName(dto.getFieldName());
                schema.setDisplayName(dto.getDisplayName() != null ? dto.getDisplayName() : dto.getFieldName());
                schema.setFieldType(dto.getFieldType() != null ? dto.getFieldType() : "TEXT");
                schema.setDescription(dto.getDescription() != null ? dto.getDescription() : "");
                schema.setRole(dto.getRole() != null ? dto.getRole() : "CUSTOM");
                schema.setRequired(dto.getRequired() != null ? dto.getRequired() : 0);
                schema.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : i);
                schemaMapper.insert(schema);
            }
        }

        // 批量写入条目
        for (int i = 0; i < items.size(); i++) {
            EvalDatasetItem item = items.get(i);
            item.setDatasetId(dataset.getId());
            item.setSeqNo(i + 1);
            datasetItemMapper.insert(item);
        }

        log.info("数据集上传成功: id={}, name={}, count={}", dataset.getId(), name, items.size());
        return dataset;
    }

    @Override
    public PageResult<EvalDataset> list(int page, int size) {
        Page<EvalDataset> pageObj = new Page<>(page, size);
        Page<EvalDataset> result = datasetMapper.selectPage(pageObj,
                new LambdaQueryWrapper<EvalDataset>().orderByDesc(EvalDataset::getCreatedAt));
        return new PageResult<>(result.getRecords(), result.getTotal(), page, size);
    }

    @Override
    public EvalDataset getById(Long id) {
        return datasetMapper.selectById(id);
    }

    @Override
    @Transactional
    public EvalDataset updateInfo(Long id, String name, String description, Integer hasReference, Integer hasModelResponse) {
        EvalDataset dataset = datasetMapper.selectById(id);
        if (dataset == null) throw new BusinessException("数据集不存在");
        if (StrUtil.isNotBlank(name)) dataset.setName(name);
        dataset.setDescription(description != null ? description : "");
        if (hasReference != null) dataset.setHasReference(hasReference);
        if (hasModelResponse != null) dataset.setHasModelResponse(hasModelResponse);
        datasetMapper.updateById(dataset);
        log.info("数据集信息更新: id={}, name={}", id, dataset.getName());
        return dataset;
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        schemaMapper.delete(new LambdaQueryWrapper<EvalDatasetSchema>()
                .eq(EvalDatasetSchema::getDatasetId, id));
        datasetItemMapper.delete(new LambdaQueryWrapper<EvalDatasetItem>()
                .eq(EvalDatasetItem::getDatasetId, id));
        datasetMapper.deleteById(id);
        log.info("数据集删除: id={}", id);
    }

    @Override
    public PageResult<EvalDatasetItem> listItems(Long datasetId, int page, int size) {
        Page<EvalDatasetItem> pageObj = new Page<>(page, size);
        Page<EvalDatasetItem> result = datasetItemMapper.selectPage(pageObj,
                new LambdaQueryWrapper<EvalDatasetItem>()
                        .eq(EvalDatasetItem::getDatasetId, datasetId)
                        .orderByAsc(EvalDatasetItem::getSeqNo));
        return new PageResult<>(result.getRecords(), result.getTotal(), page, size);
    }

    @Override
    public List<EvalDatasetSchema> getSchema(Long datasetId) {
        return schemaMapper.selectList(new LambdaQueryWrapper<EvalDatasetSchema>()
                .eq(EvalDatasetSchema::getDatasetId, datasetId)
                .orderByAsc(EvalDatasetSchema::getSortOrder));
    }

    @Override
    @Transactional
    public void updateSchema(Long datasetId, List<SchemaFieldDTO> fields) {
        // 删除旧schema
        schemaMapper.delete(new LambdaQueryWrapper<EvalDatasetSchema>()
                .eq(EvalDatasetSchema::getDatasetId, datasetId));
        // 写入新schema
        for (int i = 0; i < fields.size(); i++) {
            SchemaFieldDTO dto = fields.get(i);
            EvalDatasetSchema schema = new EvalDatasetSchema();
            schema.setDatasetId(datasetId);
            schema.setFieldName(dto.getFieldName());
            schema.setDisplayName(dto.getDisplayName() != null ? dto.getDisplayName() : dto.getFieldName());
            schema.setFieldType(dto.getFieldType() != null ? dto.getFieldType() : "TEXT");
            schema.setDescription(dto.getDescription() != null ? dto.getDescription() : "");
            schema.setRole(dto.getRole() != null ? dto.getRole() : "CUSTOM");
            schema.setRequired(dto.getRequired() != null ? dto.getRequired() : 0);
            schema.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : i);
            schemaMapper.insert(schema);
        }
    }

    @Override
    @Transactional
    public EvalDatasetItem addItem(Long datasetId, DatasetItemDTO dto) {
        EvalDatasetItem item = new EvalDatasetItem();
        item.setDatasetId(datasetId);
        item.setQuestion(dto.getQuestion());
        item.setReferenceAnswer(dto.getReferenceAnswer());
        item.setContext(dto.getContext());
        item.setCategory(dto.getCategory() != null ? dto.getCategory() : "");
        item.setExtraFields(dto.getExtraFields());
        // 获取最大seqNo
        EvalDatasetItem last = datasetItemMapper.selectOne(
                new LambdaQueryWrapper<EvalDatasetItem>()
                        .eq(EvalDatasetItem::getDatasetId, datasetId)
                        .orderByDesc(EvalDatasetItem::getSeqNo)
                        .last("LIMIT 1"));
        item.setSeqNo(last != null ? last.getSeqNo() + 1 : 1);
        datasetItemMapper.insert(item);
        // 更新总数
        EvalDataset ds = datasetMapper.selectById(datasetId);
        if (ds != null) {
            ds.setTotalCount(ds.getTotalCount() + 1);
            datasetMapper.updateById(ds);
        }
        return item;
    }

    @Override
    @Transactional
    public void updateItem(Long itemId, DatasetItemDTO dto) {
        EvalDatasetItem item = datasetItemMapper.selectById(itemId);
        if (item == null) throw new BusinessException("条目不存在");
        item.setQuestion(dto.getQuestion());
        item.setReferenceAnswer(dto.getReferenceAnswer());
        item.setContext(dto.getContext());
        item.setCategory(dto.getCategory());
        item.setExtraFields(dto.getExtraFields());
        datasetItemMapper.updateById(item);
    }

    @Override
    @Transactional
    public void deleteItem(Long itemId) {
        EvalDatasetItem item = datasetItemMapper.selectById(itemId);
        if (item == null) throw new BusinessException("条目不存在");
        datasetItemMapper.deleteById(itemId);
        EvalDataset ds = datasetMapper.selectById(item.getDatasetId());
        if (ds != null && ds.getTotalCount() > 0) {
            ds.setTotalCount(ds.getTotalCount() - 1);
            datasetMapper.updateById(ds);
        }
    }

    @Override
    public List<EvalDatasetItem> batchGetItems(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return datasetItemMapper.selectBatchIds(ids);
    }

    @Override
    public List<EvalDataset> listVersions(String name) {
        return datasetMapper.selectList(new LambdaQueryWrapper<EvalDataset>()
                .eq(EvalDataset::getName, name)
                .orderByDesc(EvalDataset::getVersion));
    }

    @Override
    public List<DatasetEvalHistoryVO> listEvalHistory(Long datasetId) {
        // 查该数据集的所有任务
        List<EvalTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<EvalTask>()
                .eq(EvalTask::getDatasetId, datasetId)
                .orderByDesc(EvalTask::getCreatedAt));
        if (tasks.isEmpty()) return List.of();

        // 批量加载关联数据，避免 N+1
        Set<Long> taskIds = tasks.stream().map(EvalTask::getId).collect(Collectors.toSet());
        Set<Long> modelIds = tasks.stream().map(EvalTask::getDatasetId).collect(Collectors.toSet());
        Set<Long> promptIds = tasks.stream().map(EvalTask::getDatasetId).collect(Collectors.toSet());

        // 任务-模型关联
        List<EvalTaskModel> taskModels = taskModelMapper.selectList(new LambdaQueryWrapper<EvalTaskModel>()
                .in(EvalTaskModel::getTaskId, taskIds));
        Map<Long, Long> taskModelMap = taskModels.stream()
                .collect(Collectors.toMap(EvalTaskModel::getTaskId, EvalTaskModel::getModelConfigId, (a, b) -> a));
        // 任务-Prompt关联
        List<EvalTaskPrompt> taskPrompts = taskPromptMapper.selectList(new LambdaQueryWrapper<EvalTaskPrompt>()
                .in(EvalTaskPrompt::getTaskId, taskIds));
        Map<Long, Long> taskPromptMap = taskPrompts.stream()
                .collect(Collectors.toMap(EvalTaskPrompt::getTaskId, EvalTaskPrompt::getPromptId, (a, b) -> a));

        // 模型名、Prompt名
        Map<Long, String> modelNameMap = modelConfigMapper.selectBatchIds(taskModelMap.values()).stream()
                .collect(Collectors.toMap(EvalModelConfig::getId, EvalModelConfig::getName, (a, b) -> a));
        Map<Long, String> promptNameMap = promptMapper.selectBatchIds(taskPromptMap.values()).stream()
                .collect(Collectors.toMap(EvalPrompt::getId, EvalPrompt::getName, (a, b) -> a));

        // 每个任务的整体 summary（dimension IS NULL）
        List<EvalTaskSummary> summaries = summaryMapper.selectList(new LambdaQueryWrapper<EvalTaskSummary>()
                .in(EvalTaskSummary::getTaskId, taskIds)
                .isNull(EvalTaskSummary::getDimension));
        Map<Long, EvalTaskSummary> summaryMap = summaries.stream()
                .collect(Collectors.toMap(EvalTaskSummary::getTaskId, s -> s, (a, b) -> a));

        List<DatasetEvalHistoryVO> result = new ArrayList<>();
        for (EvalTask t : tasks) {
            DatasetEvalHistoryVO vo = new DatasetEvalHistoryVO();
            vo.setTaskId(t.getId());
            vo.setTaskName(t.getName());
            vo.setTaskVersion(t.getVersion());
            vo.setStatus(t.getStatus());
            vo.setCreatedAt(t.getCreatedAt());

            Long modelId = taskModelMap.get(t.getId());
            if (modelId != null) vo.setModelName(modelNameMap.getOrDefault(modelId, "模型" + modelId));
            Long promptId = taskPromptMap.get(t.getId());
            if (promptId != null) vo.setPromptName(promptNameMap.getOrDefault(promptId, "Prompt" + promptId));

            EvalTaskSummary s = summaryMap.get(t.getId());
            if (s != null) {
                vo.setTotalCount(s.getTotalCount());
                vo.setBadcaseCount(s.getBadcaseCount());
                vo.setBadcaseRate(s.getBadcaseRate() != null ? s.getBadcaseRate().doubleValue() : 0);
            }
            result.add(vo);
        }
        return result;
    }

    // ========== 内部方法 ==========

    private String detectFileType(String filename) {
        if (filename == null) return "JSON";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) return "XLSX";
        if (lower.endsWith(".csv")) return "CSV";
        return "JSON";
    }

    private String guessRole(String colName) {
        if (colName == null) return "CUSTOM";
        String lower = colName.toLowerCase().trim();
        for (Map.Entry<String, String> entry : ROLE_HINTS.entrySet()) {
            if (lower.contains(entry.getKey().toLowerCase())) return entry.getValue();
        }
        return "CUSTOM";
    }

    /** 根据Schema角色映射，解析文件数据到EvalDatasetItem */
    private List<EvalDatasetItem> parseFile(MultipartFile file, String fileType, List<SchemaFieldDTO> schemaFields) {
        // 构建字段名→角色映射
        Map<String, String> fieldRoleMap = new HashMap<>();
        if (schemaFields != null) {
            for (SchemaFieldDTO dto : schemaFields) {
                fieldRoleMap.put(dto.getFieldName(), dto.getRole());
            }
        }

        try (InputStream is = file.getInputStream()) {
            List<EvalDatasetItem> items = new ArrayList<>();

            if ("XLSX".equals(fileType)) {
                Workbook wb = new XSSFWorkbook(is);
                Sheet sheet = wb.getSheetAt(0);
                Row headerRow = sheet.getRow(0);
                List<String> headers = new ArrayList<>();
                for (Cell cell : headerRow) headers.add(getCellStringValue(cell));

                for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);
                    if (row == null) continue;
                    EvalDatasetItem item = buildItemFromRow(headers, row, fieldRoleMap);
                    if (!isItemEmpty(item)) items.add(item);
                }
                wb.close();
            } else if ("JSON".equals(fileType)) {
                String content = new String(is.readAllBytes(), "UTF-8");
                List<Object> rawList = JSONUtil.toList(content, Object.class);
                for (Object obj : rawList) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) obj;
                    EvalDatasetItem item = buildItemFromMap(m, fieldRoleMap);
                    if (!isItemEmpty(item)) items.add(item);
                }
            } else {
                String content = new String(is.readAllBytes(), "UTF-8");
                String[] lines = content.split("\n");
                String[] headers = lines[0].trim().split(",", -1);
                for (int i = 1; i < lines.length; i++) {
                    String line = lines[i].trim();
                    if (StrUtil.isBlank(line)) continue;
                    String[] parts = line.split(",", -1);
                    Map<String, String> m = new LinkedHashMap<>();
                    for (int j = 0; j < headers.length; j++) {
                        m.put(headers[j].trim(), j < parts.length ? parts[j].trim() : "");
                    }
                    EvalDatasetItem item = buildItemFromFlatMap(m, fieldRoleMap);
                    if (!isItemEmpty(item)) items.add(item);
                }
            }

            if (items.isEmpty()) throw new BusinessException("解析文件后无有效数据");
            return items;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析数据集文件失败", e);
            throw new BusinessException("解析数据集文件失败: " + e.getMessage());
        }
    }

    /** XLSX: 从行数据构建条目 */
    private EvalDatasetItem buildItemFromRow(List<String> headers, Row row, Map<String, String> fieldRoleMap) {
        EvalDatasetItem item = new EvalDatasetItem();
        Map<String, String> extra = new LinkedHashMap<>();
        for (int j = 0; j < headers.size(); j++) {
            String col = headers.get(j);
            String role = fieldRoleMap.getOrDefault(col, guessRole(col));
            String val = j < row.getPhysicalNumberOfCells() && row.getCell(j) != null
                    ? getCellStringValue(row.getCell(j)) : "";
            switch (role) {
                case "QUESTION": item.setQuestion(val); break;
                case "REFERENCE": item.setReferenceAnswer(val); break;
                case "CONTEXT": item.setContext(val); break;
                case "CATEGORY": item.setCategory(val); break;
                default: if (!val.isEmpty()) extra.put(col, val); break;
            }
        }
        if (!extra.isEmpty()) item.setExtraFields(JSONUtil.toJsonStr(extra));
        if (item.getCategory() == null) item.setCategory("");
        if (item.getReferenceAnswer() == null) item.setReferenceAnswer("");
        if (item.getContext() == null) item.setContext("");
        return item;
    }

    /** JSON: 从Map构建条目 */
    private EvalDatasetItem buildItemFromMap(Map<String, Object> m, Map<String, String> fieldRoleMap) {
        EvalDatasetItem item = new EvalDatasetItem();
        Map<String, String> extra = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : m.entrySet()) {
            String col = entry.getKey();
            String role = fieldRoleMap.getOrDefault(col, guessRole(col));
            String val = entry.getValue() != null ? String.valueOf(entry.getValue()) : "";
            switch (role) {
                case "QUESTION": item.setQuestion(val); break;
                case "REFERENCE": item.setReferenceAnswer(val); break;
                case "CONTEXT": item.setContext(val); break;
                case "CATEGORY": item.setCategory(val); break;
                default: if (!val.isEmpty()) extra.put(col, val); break;
            }
        }
        if (!extra.isEmpty()) item.setExtraFields(JSONUtil.toJsonStr(extra));
        if (item.getCategory() == null) item.setCategory("");
        if (item.getReferenceAnswer() == null) item.setReferenceAnswer("");
        if (item.getContext() == null) item.setContext("");
        return item;
    }

    /** CSV: 从扁平Map构建条目 */
    private EvalDatasetItem buildItemFromFlatMap(Map<String, String> m, Map<String, String> fieldRoleMap) {
        EvalDatasetItem item = new EvalDatasetItem();
        Map<String, String> extra = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : m.entrySet()) {
            String col = entry.getKey();
            String role = fieldRoleMap.getOrDefault(col, guessRole(col));
            String val = entry.getValue();
            switch (role) {
                case "QUESTION": item.setQuestion(val); break;
                case "REFERENCE": item.setReferenceAnswer(val); break;
                case "CONTEXT": item.setContext(val); break;
                case "CATEGORY": item.setCategory(val); break;
                default: if (!val.isEmpty()) extra.put(col, val); break;
            }
        }
        if (!extra.isEmpty()) item.setExtraFields(JSONUtil.toJsonStr(extra));
        if (item.getCategory() == null) item.setCategory("");
        if (item.getReferenceAnswer() == null) item.setReferenceAnswer("");
        if (item.getContext() == null) item.setContext("");
        return item;
    }

    private String getCellStringValue(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING: return cell.getStringCellValue().trim();
            case NUMERIC: return String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            default: return "";
        }
    }

    /** 判断条目是否完全为空（没有任何有效内容） */
    private boolean isItemEmpty(EvalDatasetItem item) {
        boolean coreEmpty = (item.getQuestion() == null || item.getQuestion().isEmpty())
                && (item.getReferenceAnswer() == null || item.getReferenceAnswer().isEmpty())
                && (item.getContext() == null || item.getContext().isEmpty())
                && (item.getCategory() == null || item.getCategory().isEmpty());
        boolean extraEmpty = (item.getExtraFields() == null || item.getExtraFields().isEmpty()
                || "{}".equals(item.getExtraFields()));
        return coreEmpty && extraEmpty;
    }
}
