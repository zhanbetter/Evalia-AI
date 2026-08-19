package com.eval.web.controller;

import com.eval.common.result.PageResult;
import com.eval.common.result.Result;
import com.eval.model.dto.DatasetItemDTO;
import com.eval.model.dto.DuplicateDetectionResult;
import com.eval.model.dto.SchemaFieldDTO;
import com.eval.model.entity.EvalDataset;
import com.eval.model.entity.EvalDatasetItem;
import com.eval.model.entity.EvalDatasetSchema;
import com.eval.service.DatasetService;
import com.eval.service.DuplicateDetectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 数据集管理
 */
@RestController
@RequestMapping("/api/datasets")
@RequiredArgsConstructor
public class DatasetController {

    private final DatasetService datasetService;
    private final DuplicateDetectionService duplicateDetectionService;

    /** 上传预览：解析文件列名+前几行数据+推荐映射 */
    @PostMapping("/preview")
    public Result<Map<String, Object>> previewFile(@RequestParam("file") MultipartFile file) {
        return Result.success(datasetService.previewFile(file));
    }

    /** 上传数据集（含字段映射） */
    @PostMapping("/upload")
    public Result<EvalDataset> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("name") String name,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "hasReference", required = false) Integer hasReference,
            @RequestParam(value = "hasModelResponse", required = false) Integer hasModelResponse,
            @RequestParam(value = "schemaFields", required = false) String schemaFieldsJson,
            @RequestParam(value = "columnMapping", required = false) String columnMapping) {
        List<SchemaFieldDTO> schemaFields = null;
        if (schemaFieldsJson != null && !schemaFieldsJson.isEmpty()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                schemaFields = mapper.readValue(schemaFieldsJson,
                        mapper.getTypeFactory().constructCollectionType(List.class, SchemaFieldDTO.class));
            } catch (Exception e) {
                // 忽略解析失败，按无schema处理
            }
        }
        return Result.success(datasetService.upload(file, name, description, hasReference, hasModelResponse, schemaFields, columnMapping));
    }

    /** 数据集列表 */
    @GetMapping
    public Result<PageResult<EvalDataset>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.success(datasetService.list(page, size));
    }

    /** 数据集详情 */
    @GetMapping("/{id}")
    public Result<EvalDataset> getById(@PathVariable Long id) {
        return Result.success(datasetService.getById(id));
    }

    /** 更新数据集基本信息（名称、描述、评测类型、模型结果） */
    @PutMapping("/{id}")
    public Result<EvalDataset> update(@PathVariable Long id,
                                      @RequestParam(required = false) String name,
                                      @RequestParam(required = false) String description,
                                      @RequestParam(required = false) Integer hasReference,
                                      @RequestParam(required = false) Integer hasModelResponse) {
        return Result.success(datasetService.updateInfo(id, name, description, hasReference, hasModelResponse));
    }

    /** 删除数据集 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        datasetService.deleteById(id);
        return Result.success();
    }

    /** 数据集条目列表 */
    @GetMapping("/{id}/items")
    public Result<PageResult<EvalDatasetItem>> listItems(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(datasetService.listItems(id, page, size));
    }

    /** 批量查询数据集条目（按ID列表，用于报告页Badcase详情） */
    @GetMapping("/items/batch")
    public Result<List<EvalDatasetItem>> batchGetItems(@RequestParam List<Long> ids) {
        return Result.success(datasetService.batchGetItems(ids));
    }

    /** 获取数据集Schema */
    @GetMapping("/{id}/schema")
    public Result<List<EvalDatasetSchema>> getSchema(@PathVariable Long id) {
        return Result.success(datasetService.getSchema(id));
    }

    /** 更新Schema */
    @PutMapping("/{id}/schema")
    public Result<Void> updateSchema(@PathVariable Long id, @RequestBody List<SchemaFieldDTO> fields) {
        datasetService.updateSchema(id, fields);
        return Result.success();
    }

    /** 手动添加条目 */
    @PostMapping("/{id}/items")
    public Result<EvalDatasetItem> addItem(@PathVariable Long id, @RequestBody DatasetItemDTO dto) {
        return Result.success(datasetService.addItem(id, dto));
    }

    /** 编辑条目 */
    @PutMapping("/items/{itemId}")
    public Result<Void> updateItem(@PathVariable Long itemId, @RequestBody DatasetItemDTO dto) {
        datasetService.updateItem(itemId, dto);
        return Result.success();
    }

    /** 删除条目 */
    @DeleteMapping("/items/{itemId}")
    public Result<Void> deleteItem(@PathVariable Long itemId) {
        datasetService.deleteItem(itemId);
        return Result.success();
    }

    /** 获取数据集版本列表 */
    @GetMapping("/versions")
    public Result<List<EvalDataset>> listVersions(@RequestParam String name) {
        return Result.success(datasetService.listVersions(name));
    }

    /** 获取数据集的评测历史 */
    @GetMapping("/{id}/eval-history")
    public Result<List<com.eval.model.vo.DatasetEvalHistoryVO>> listEvalHistory(@PathVariable Long id) {
        return Result.success(datasetService.listEvalHistory(id));
    }

    /** 重复检测 */
    @PostMapping("/{id}/detect-duplicates")
    public Result<DuplicateDetectionResult> detectDuplicates(
            @PathVariable Long id,
            @RequestParam(defaultValue = "question") String fieldName,
            @RequestParam(defaultValue = "0.8") double threshold) {
        return Result.success(duplicateDetectionService.detect(id, fieldName, threshold));
    }

    /** 批量删除条目 */
    @PostMapping("/{id}/items/batch-delete")
    public Result<Void> batchDeleteItems(@PathVariable Long id, @RequestBody List<Long> itemIds) {
        datasetService.batchDeleteItems(id, itemIds);
        return Result.success();
    }
}
