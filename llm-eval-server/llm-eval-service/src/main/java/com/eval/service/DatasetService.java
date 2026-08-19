package com.eval.service;

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

    /** 上传数据集文件并解析入库（含字段映射） */
    EvalDataset upload(MultipartFile file, String name, String description, Integer hasReference,
                       Integer hasModelResponse,
                       List<SchemaFieldDTO> schemaFields, String columnMapping);

    /** 上传预览：解析文件列名+前几行数据 */
    Map<String, Object> previewFile(MultipartFile file);

    /** 数据集列表（分页） */
    PageResult<EvalDataset> list(int page, int size);

    /** 数据集详情 */
    EvalDataset getById(Long id);

    /** 更新数据集基本信息（名称、描述、评测类型） */
    EvalDataset updateInfo(Long id, String name, String description, Integer hasReference, Integer hasModelResponse);

    /** 删除数据集 */
    void deleteById(Long id);

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

    /** 删除条目 */
    void deleteItem(Long itemId);

    /** 批量删除条目 */
    void batchDeleteItems(Long datasetId, List<Long> itemIds);

    /** 获取数据集版本列表 */
    List<EvalDataset> listVersions(String name);

    /** 获取数据集的评测历史（该数据集被哪些任务测过，每个任务的结果） */
    List<com.eval.model.vo.DatasetEvalHistoryVO> listEvalHistory(Long datasetId);
}
