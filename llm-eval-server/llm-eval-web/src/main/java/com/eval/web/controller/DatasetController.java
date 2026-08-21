package com.eval.web.controller;

import com.eval.common.constant.AsyncJobType;
import com.eval.common.auth.AuthContext;
import com.eval.common.exception.BusinessException;
import com.eval.common.result.PageResult;
import com.eval.common.result.Result;
import com.eval.model.dto.DatasetItemDTO;
import com.eval.model.dto.GoldAnnotateDTO;
import com.eval.model.dto.SchemaFieldDTO;
import com.eval.model.entity.EvalDataset;
import com.eval.model.entity.EvalDatasetItem;
import com.eval.model.entity.EvalDatasetSchema;
import com.eval.model.entity.EvalGoldAnnotation;
import com.eval.model.vo.AsyncJobVO;
import com.eval.model.vo.GoldAnnotationItemVO;
import com.eval.model.vo.GoldAnnotationStatsVO;
import com.eval.service.AsyncJobService;
import com.eval.service.DatasetService;
import com.eval.service.GoldAnnotationService;
import com.eval.web.interceptor.AuthInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据集管理
 */
@RestController
@RequestMapping("/api/datasets")
@RequiredArgsConstructor
@Slf4j
public class DatasetController {

    private final DatasetService datasetService;
    private final AsyncJobService asyncJobService;
    private final GoldAnnotationService goldAnnotationService;

    /** 上传预览：解析文件列名+前几行数据+推荐映射 */
    @PostMapping("/preview")
    public Result<Map<String, Object>> previewFile(@RequestParam("file") MultipartFile file) {
        return Result.success(datasetService.previewFile(file));
    }

    /** 上传数据集（含字段映射）
     *  parentId 为空 = 新建数据集（同名拒绝）；parentId 有值 = 为该数据集提交新版本（允许同名升版本） */
    @PostMapping("/upload")
    public Result<EvalDataset> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("name") String name,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "hasReference", required = false) Integer hasReference,
            @RequestParam(value = "hasModelResponse", required = false) Integer hasModelResponse,
            @RequestParam(value = "schemaFields", required = false) String schemaFieldsJson,
            @RequestParam(value = "columnMapping", required = false) String columnMapping,
            @RequestParam(value = "parentId", required = false) Long parentId,
            @RequestAttribute(value = AuthInterceptor.AUTH_ATTR) AuthContext ctx) {
        log.info("上传请求: name={}, hasRef={}, hasModelResp={}, schemaFieldsJson长度={}",
                name, hasReference, hasModelResponse,
                schemaFieldsJson != null ? schemaFieldsJson.length() : "null");
        List<SchemaFieldDTO> schemaFields = null;
        if (schemaFieldsJson != null && !schemaFieldsJson.isEmpty()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                // 配置：忽略未知属性（如 selected），不严格校验
                mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                schemaFields = mapper.readValue(schemaFieldsJson,
                        mapper.getTypeFactory().constructCollectionType(List.class, SchemaFieldDTO.class));
                log.info("解析schemaFields成功，共{}个字段: {}", schemaFields.size(),
                        schemaFields.stream().map(f -> f.getFieldName() + "(" + f.getRole() + ")").reduce((a, b) -> a + ", " + b).orElse(""));
            } catch (Exception e) {
                log.error("解析schemaFields失败: input={}", schemaFieldsJson.length() > 500 ? schemaFieldsJson.substring(0, 500) + "..." : schemaFieldsJson, e);
            }
        } else {
            log.warn("上传时schemaFields为空: schemaFieldsJson={}", schemaFieldsJson);
        }
        return Result.success(datasetService.upload(file, name, description, hasReference, hasModelResponse, schemaFields, columnMapping, parentId, ctx.getUserId()));
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

    /** 删除数据集（仅创建者或管理员可删） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               @RequestAttribute(value = AuthInterceptor.AUTH_ATTR) AuthContext ctx) {
        datasetService.deleteById(id, ctx);
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

    /** 删除条目（沿用所属数据集的删除保护） */
    @DeleteMapping("/items/{itemId}")
    public Result<Void> deleteItem(@PathVariable Long itemId,
                                   @RequestAttribute(value = AuthInterceptor.AUTH_ATTR) AuthContext ctx) {
        datasetService.deleteItem(itemId, ctx);
        return Result.success();
    }

    /** 获取数据集版本列表 */
    @GetMapping("/versions")
    public Result<List<EvalDataset>> listVersions(@RequestParam String name) {
        return Result.success(datasetService.listVersions(name));
    }

    /** 检测数据集名称占用情况（上传/编辑时前端调用）
     *  excludeName：排除的名称组（改名单实体时传原名称，排除其所有版本） */
    @GetMapping("/check-name")
    public Result<com.eval.model.dto.NameCheckResult> checkName(
            @RequestParam String name,
            @RequestParam(required = false) Long excludeId,
            @RequestParam(required = false) String excludeName,
            @RequestParam(required = false) Integer targetVersion) {
        return Result.success(datasetService.checkName(name, excludeId, excludeName, targetVersion));
    }

    /** 获取数据集的评测历史 */
    @GetMapping("/{id}/eval-history")
    public Result<List<com.eval.model.vo.DatasetEvalHistoryVO>> listEvalHistory(@PathVariable Long id) {
        return Result.success(datasetService.listEvalHistory(id));
    }

    /**
     * 数据重复检测（异步任务）
     * 提交后立即返回 jobId，前端轮询 GET /api/async-jobs/{id} 查看进度，完成后取结果
     */
    @PostMapping("/{id}/detect-duplicates")
    public Result<AsyncJobVO> detectDuplicates(
            @PathVariable Long id,
            @RequestParam(defaultValue = "question") String fieldName,
            @RequestParam(defaultValue = "0.8") double threshold) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("datasetId", id);
        payload.put("fieldName", fieldName);
        payload.put("threshold", threshold);
        return Result.success(asyncJobService.toVO(
                asyncJobService.submit(AsyncJobType.DETECT_DUPLICATES, toPayloadJson(payload))));
    }

    private String toPayloadJson(Map<String, Object> data) {
        try {
            return new ObjectMapper().writeValueAsString(data);
        } catch (Exception e) {
            throw new BusinessException("任务参数序列化失败: " + e.getMessage());
        }
    }

    /** 批量删除条目（沿用所属数据集的删除保护） */
    @PostMapping("/{id}/items/batch-delete")
    public Result<Void> batchDeleteItems(@PathVariable Long id, @RequestBody List<Long> itemIds,
                                         @RequestAttribute(value = AuthInterceptor.AUTH_ATTR) AuthContext ctx) {
        datasetService.batchDeleteItems(id, itemIds, ctx);
        return Result.success();
    }

    // ======================== 金标准标注台（条目级，脱离任务/模型） ========================

    /** 标注本体：同一标注者对同一条目为 upsert（覆盖旧判定） */
    @PostMapping("/{id}/gold-annotations/annotate")
    public Result<EvalGoldAnnotation> goldAnnotate(@PathVariable Long id, @Valid @RequestBody GoldAnnotateDTO dto) {
        return Result.success(goldAnnotationService.annotate(id, dto));
    }

    /** 数据集标注条目列表（分页，含每条的投片统计与多数表决结论） */
    @GetMapping("/{id}/gold-annotations")
    public Result<PageResult<GoldAnnotationItemVO>> listGoldAnnotations(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(goldAnnotationService.listItemAnnotations(id, page, size));
    }

    /** 一致性统计：覆盖率 / 一致率 / Fleiss Kappa / 标注者分布 */
    @GetMapping("/{id}/gold-annotations/stats")
    public Result<GoldAnnotationStatsVO> goldAnnotationStats(@PathVariable Long id) {
        return Result.success(goldAnnotationService.stats(id));
    }

    /** 删除某标注者对某条目的标注 */
    @DeleteMapping("/{id}/gold-annotations/{itemId}")
    public Result<Void> removeGoldAnnotation(@PathVariable Long id, @PathVariable Long itemId,
                                             @RequestParam String annotator) {
        goldAnnotationService.remove(id, itemId, annotator);
        return Result.success();
    }
}
