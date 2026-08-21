package com.eval.service;

import com.eval.common.result.PageResult;
import com.eval.model.dto.GoldAnnotateDTO;
import com.eval.model.entity.EvalGoldAnnotation;
import com.eval.model.vo.GoldAnnotationItemVO;
import com.eval.model.vo.GoldAnnotationStatsVO;

/**
 * 金标准标注服务（条目级，脱离任务/被测模型的独立标注台）
 */
public interface GoldAnnotationService {

    /** 标注/更新：同一标注者对同一条目为 upsert（覆盖旧判定） */
    EvalGoldAnnotation annotate(Long datasetId, GoldAnnotateDTO dto);

    /** 数据集标注条目列表（分页，含每条的投片统计与多数表决结论） */
    PageResult<GoldAnnotationItemVO> listItemAnnotations(Long datasetId, int page, int size);

    /** 一致性统计：覆盖率 / 一致率 / Fleiss Kappa / 标注者分布 */
    GoldAnnotationStatsVO stats(Long datasetId);

    /** 删除某标注者对某条目的标注 */
    void remove(Long datasetId, Long itemId, String annotator);
}