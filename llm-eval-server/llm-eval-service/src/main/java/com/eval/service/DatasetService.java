package com.eval.service;

import com.eval.common.auth.AuthContext;
import com.eval.common.result.PageResult;
import com.eval.model.dto.DatasetItemDTO;
import com.eval.model.dto.SchemaFieldDTO;
import com.eval.model.entity.EvalDataset;
import com.eval.model.entity.EvalDatasetItem;
import com.eval.model.entity.EvalDatasetSchema;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface DatasetService {

    /** 上传数据集文件并解析入库（含字段映射）
     *  @param parentId 提交新版本时传父数据集ID（允许同名升版本）；新建数据集为 null（同名将拒绝）
     *  @param createdBy 创建者ID（eval_user.id），用于删除保护 */
    EvalDataset upload(MultipartFile file, String name, String description, Integer hasReference,
                       Integer hasModelResponse,
                       List<SchemaFieldDTO> schemaFields, String columnMapping, Long parentId, Long createdBy);

    /** 上传预览：解析文件列名+前几行数据 */
    Map<String, Object> previewFile(MultipartFile file);

    /** 数据集列表（分页） */
    PageResult<EvalDataset> list(int page, int size);

    /** 数据集详情 */
    EvalDataset getById(Long id);

    /** 更新数据集基本信息（名称、描述、评测类型） */
    EvalDataset updateInfo(Long id, String name, String description, Integer hasReference, Integer hasModelResponse);

    /** 删除数据集（仅创建者或管理员可删） */
    void deleteById(Long id, AuthContext ctx);

    /** 数据集条目列表（分页） */
    PageResult<EvalDatasetItem> listItems(Long datasetId, int page, int size);

    /** 批量查询数据集条目（按ID列表） */
    List<EvalDatasetItem> batchGetItems(List<Long> ids);

    /** 获取数据集Schema */
    List<EvalDatasetSchema> getSchema(Long datasetId);

    /** 更新Schema字段 */
    void updateSchema(Long datasetId, List<SchemaFieldDTO> fields);

    /** 手动添加条目 */
    EvalDatasetItem addItem(Long datasetId, DatasetItemDTO dto);

    /** 编辑条目 */
    void updateItem(Long itemId, DatasetItemDTO dto);

    /** 删除条目（沿用所属数据集的删除保护） */
    void deleteItem(Long itemId, AuthContext ctx);

    /** 批量删除条目（沿用所属数据集的删除保护） */
    void batchDeleteItems(Long datasetId, List<Long> itemIds, AuthContext ctx);

    /** 获取数据集版本列表 */
    List<EvalDataset> listVersions(String name);

    /**
     * 检测数据集名称占用情况
     * @param excludeDatasetId 排除的数据集ID（编辑重命名时排除自身），可为null
     * @param excludeName 排除的名称组（同一名称下的所有版本算一个实体，改名时排除自身名称组），可为null
     * @param targetVersion 若指定，则校验该版本是否已被占用
     */
    com.eval.model.dto.NameCheckResult checkName(String name, Long excludeDatasetId, String excludeName, Integer targetVersion);

    /** 获取数据集的评测历史（该数据集被哪些任务测过，每个任务的结果） */
    List<com.eval.model.vo.DatasetEvalHistoryVO> listEvalHistory(Long datasetId);
}
