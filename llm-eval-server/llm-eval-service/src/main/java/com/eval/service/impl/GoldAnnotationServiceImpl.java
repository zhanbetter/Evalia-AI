package com.eval.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eval.common.exception.BusinessException;
import com.eval.common.result.PageResult;
import com.eval.dao.mapper.EvalDatasetItemMapper;
import com.eval.dao.mapper.EvalGoldAnnotationMapper;
import com.eval.model.dto.GoldAnnotateDTO;
import com.eval.model.dto.GoldAnnotatorRow;
import com.eval.model.dto.GoldVoteRow;
import com.eval.model.entity.EvalDatasetItem;
import com.eval.model.entity.EvalGoldAnnotation;
import com.eval.model.vo.GoldAnnotationEntryVO;
import com.eval.model.vo.GoldAnnotationItemVO;
import com.eval.model.vo.GoldAnnotationStatsVO;
import com.eval.service.DatasetService;
import com.eval.service.GoldAnnotationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 金标准标注服务实现
 *
 * 多数表决：good 票 > bad 票 → goodcase；反之 badcase；等票或仅一人时为平票（verdict=null）。
 * Fleiss Kappa 固定两类（good/bad）、条目数可变，仅纳入 ≥2 人标注的条目。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoldAnnotationServiceImpl implements GoldAnnotationService {

    private final EvalGoldAnnotationMapper goldMapper;
    private final EvalDatasetItemMapper datasetItemMapper;
    private final DatasetService datasetService;

    @Override
    public EvalGoldAnnotation annotate(Long datasetId, GoldAnnotateDTO dto) {
        EvalDatasetItem item = datasetItemMapper.selectOne(new LambdaQueryWrapper<EvalDatasetItem>()
                .eq(EvalDatasetItem::getId, dto.getDatasetItemId())
                .eq(EvalDatasetItem::getDatasetId, datasetId));
        if (item == null) {
            throw new BusinessException("数据集条目不存在或不属于该数据集");
        }
        if (dto.getIsBadcase() != null && dto.getIsBadcase() != 0 && dto.getIsBadcase() != 1) {
            throw new BusinessException("标注结论只能为 0（goodcase）或 1（badcase）");
        }
        String role = StrUtil.blankToDefault(dto.getRole(), "ANNOTATOR");

        EvalGoldAnnotation existing = goldMapper.selectOne(new LambdaQueryWrapper<EvalGoldAnnotation>()
                .eq(EvalGoldAnnotation::getDatasetItemId, dto.getDatasetItemId())
                .eq(EvalGoldAnnotation::getAnnotator, dto.getAnnotator()));
        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            existing.setRole(role);
            existing.setIsBadcase(dto.getIsBadcase());
            existing.setComment(dto.getComment());
            existing.setUpdatedAt(now);
            goldMapper.updateById(existing);
            return existing;
        }

        EvalGoldAnnotation ann = new EvalGoldAnnotation();
        ann.setDatasetItemId(dto.getDatasetItemId());
        ann.setAnnotator(dto.getAnnotator());
        ann.setRole(role);
        ann.setIsBadcase(dto.getIsBadcase());
        ann.setComment(dto.getComment());
        ann.setCreatedAt(now);
        ann.setUpdatedAt(now);
        goldMapper.insert(ann);
        return ann;
    }

    @Override
    public PageResult<GoldAnnotationItemVO> listItemAnnotations(Long datasetId, int page, int size) {
        PageResult<EvalDatasetItem> items = datasetService.listItems(datasetId, page, size);
        List<GoldAnnotationItemVO> vos = new ArrayList<>();
        List<EvalDatasetItem> records = items.getRecords();
        if (records != null && !records.isEmpty()) {
            List<Long> itemIds = records.stream().map(EvalDatasetItem::getId).collect(Collectors.toList());
            List<EvalGoldAnnotation> anns = goldMapper.selectList(new LambdaQueryWrapper<EvalGoldAnnotation>()
                    .in(EvalGoldAnnotation::getDatasetItemId, itemIds)
                    .orderByAsc(EvalGoldAnnotation::getCreatedAt));
            Map<Long, List<EvalGoldAnnotation>> byItem = anns.stream()
                    .collect(Collectors.groupingBy(EvalGoldAnnotation::getDatasetItemId));
            for (EvalDatasetItem r : records) {
                vos.add(toItemVO(r, byItem.getOrDefault(r.getId(), List.of())));
            }
        }
        return new PageResult<>(vos, items.getTotal(), page, size);
    }

    @Override
    public GoldAnnotationStatsVO stats(Long datasetId) {
        GoldAnnotationStatsVO stats = new GoldAnnotationStatsVO();

        Long total = datasetItemMapper.selectCount(new LambdaQueryWrapper<EvalDatasetItem>()
                .eq(EvalDatasetItem::getDatasetId, datasetId));
        stats.setTotalItems(total);

        List<GoldVoteRow> votes = goldMapper.countVotesByItem(datasetId);
        Map<Long, List<GoldVoteRow>> byItem = votes.stream()
                .collect(Collectors.groupingBy(GoldVoteRow::getDatasetItemId));

        long annotated = byItem.size();
        long annoCount = votes.stream().mapToLong(GoldVoteRow::getCnt).sum();
        stats.setAnnotatedItems(annotated);
        stats.setAnnotationCount(annoCount);
        stats.setCoverageRate(total == null || total == 0 ? null : round3d((double) annotated / total));

        long multi = 0, agreed = 0;
        List<Double> piList = new ArrayList<>();
        long cat0 = 0, cat1 = 0;
        for (List<GoldVoteRow> rows : byItem.values()) {
            long good = 0, bad = 0;
            for (GoldVoteRow r : rows) {
                if (r.getIsBadcase() != null && r.getIsBadcase() == 1) {
                    bad += r.getCnt();
                } else {
                    good += r.getCnt();
                }
            }
            long n = good + bad;
            cat0 += good;
            cat1 += bad;
            if (n >= 2) {
                multi++;
                if (good == 0 || bad == 0) {
                    agreed++;
                }
                piList.add((double) (good * good + bad * bad - n) / (n * (n - 1)));
            }
        }
        stats.setMultiAnnotatedItems(multi);
        stats.setAgreedItems(agreed);
        stats.setAgreementRate(multi == 0 ? null : round3d((double) agreed / multi));

        // Fleiss Kappa（两类 good/bad，条目标注人数可变时用涟漪公式）
        double pBar = piList.isEmpty() ? 0 : piList.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        long totalVotes = cat0 + cat1;
        double pe = totalVotes == 0 ? 0 : (double) (cat0 * cat0 + cat1 * cat1) / (totalVotes * totalVotes);
        if (piList.isEmpty()) {
            stats.setFleissKappa(null);
        } else {
            double denom = 1 - pe;
            if (Math.abs(denom) < 1e-9) {
                stats.setFleissKappa(pBar >= 1 - 1e-9 ? 1.0 : null);
            } else {
                stats.setFleissKappa(round3d((pBar - pe) / denom));
            }
        }

        // 标注者分布
        List<GoldAnnotatorRow> rows = goldMapper.countByAnnotator(datasetId);
        Map<String, GoldAnnotationStatsVO.AnnotatorStat> byAnnotator = new LinkedHashMap<>();
        for (GoldAnnotatorRow r : rows) {
            GoldAnnotationStatsVO.AnnotatorStat st = byAnnotator.computeIfAbsent(r.getAnnotator(), k -> {
                GoldAnnotationStatsVO.AnnotatorStat s = new GoldAnnotationStatsVO.AnnotatorStat();
                s.setAnnotator(r.getAnnotator());
                s.setRole(r.getRole());
                s.setGoodCount(0);
                s.setBadCount(0);
                s.setTotalCount(0);
                return s;
            });
            long cnt = r.getCnt() == null ? 0 : r.getCnt();
            if (r.getIsBadcase() != null && r.getIsBadcase() == 1) {
                st.setBadCount(st.getBadCount() + (int) cnt);
            } else {
                st.setGoodCount(st.getGoodCount() + (int) cnt);
            }
            st.setTotalCount(st.getTotalCount() + (int) cnt);
        }
        List<GoldAnnotationStatsVO.AnnotatorStat> statList = new ArrayList<>(byAnnotator.values());
        statList.sort(Comparator.comparing(GoldAnnotationStatsVO.AnnotatorStat::getTotalCount, Comparator.reverseOrder()));
        stats.setAnnotatorStats(statList);

        return stats;
    }

    @Override
    public void remove(Long datasetId, Long itemId, String annotator) {
        EvalDatasetItem item = datasetItemMapper.selectOne(new LambdaQueryWrapper<EvalDatasetItem>()
                .eq(EvalDatasetItem::getId, itemId)
                .eq(EvalDatasetItem::getDatasetId, datasetId));
        if (item == null) {
            throw new BusinessException("数据集条目不存在或不属于该数据集");
        }
        goldMapper.delete(new LambdaQueryWrapper<EvalGoldAnnotation>()
                .eq(EvalGoldAnnotation::getDatasetItemId, itemId)
                .eq(EvalGoldAnnotation::getAnnotator, annotator));
    }

    // ======================== 私有工具 ========================

    private GoldAnnotationItemVO toItemVO(EvalDatasetItem item, List<EvalGoldAnnotation> anns) {
        GoldAnnotationItemVO vo = new GoldAnnotationItemVO();
        vo.setId(item.getId());
        vo.setSeqNo(item.getSeqNo());
        vo.setQuestion(item.getQuestion());
        vo.setReferenceAnswer(item.getReferenceAnswer());
        vo.setContext(item.getContext());
        vo.setCategory(item.getCategory());
        vo.setExtraFields(item.getExtraFields());

        int good = 0, bad = 0;
        List<GoldAnnotationEntryVO> entries = new ArrayList<>();
        for (EvalGoldAnnotation a : anns) {
            if (a.getIsBadcase() != null && a.getIsBadcase() == 1) {
                bad++;
            } else {
                good++;
            }
            GoldAnnotationEntryVO e = new GoldAnnotationEntryVO();
            e.setId(a.getId());
            e.setAnnotator(a.getAnnotator());
            e.setRole(a.getRole());
            e.setIsBadcase(a.getIsBadcase());
            e.setComment(a.getComment());
            e.setCreatedAt(a.getCreatedAt());
            entries.add(e);
        }

        vo.setGoodCount(good);
        vo.setBadCount(bad);
        vo.setAnnotationCount(anns.size());
        if (anns.isEmpty()) {
            vo.setVerdict(null);
            vo.setHasDisagreement(false);
        } else {
            if (good > bad) {
                vo.setVerdict(0);
            } else if (bad > good) {
                vo.setVerdict(1);
            } else {
                vo.setVerdict(null);
            }
            vo.setHasDisagreement(good > 0 && bad > 0);
        }
        vo.setAnnotations(entries);
        return vo;
    }

    private double round3d(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}