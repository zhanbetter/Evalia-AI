package com.eval.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eval.model.dto.GoldAnnotatorRow;
import com.eval.model.dto.GoldVoteRow;
import com.eval.model.entity.EvalGoldAnnotation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EvalGoldAnnotationMapper extends BaseMapper<EvalGoldAnnotation> {

    /** 按条目 × 判定聚合标注数（供覆盖率/一致率/多数表决/Fleiss Kappa 计算） */
    @Select("SELECT a.dataset_item_id AS datasetItemId, a.is_badcase AS isBadcase, COUNT(*) AS cnt " +
            "FROM eval_gold_annotation a " +
            "JOIN eval_dataset_item i ON a.dataset_item_id = i.id " +
            "WHERE i.dataset_id = #{datasetId} " +
            "GROUP BY a.dataset_item_id, a.is_badcase")
    List<GoldVoteRow> countVotesByItem(@Param("datasetId") Long datasetId);

    /** 按标注者 × 角色 × 判定聚合标注数（供标注者分布统计） */
    @Select("SELECT a.annotator AS annotator, a.role AS role, a.is_badcase AS isBadcase, COUNT(*) AS cnt " +
            "FROM eval_gold_annotation a " +
            "JOIN eval_dataset_item i ON a.dataset_item_id = i.id " +
            "WHERE i.dataset_id = #{datasetId} " +
            "GROUP BY a.annotator, a.role, a.is_badcase")
    List<GoldAnnotatorRow> countByAnnotator(@Param("datasetId") Long datasetId);
}