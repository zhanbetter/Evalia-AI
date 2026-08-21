package com.eval.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eval.dao.mapper.EvalDatasetItemMapper;
import com.eval.model.dto.DuplicateDetectionResult;
import com.eval.model.dto.DuplicateDetectionResult.GroupItem;
import com.eval.model.dto.DuplicateDetectionResult.DuplicateGroup;
import com.eval.model.entity.EvalDatasetItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.BiConsumer;

/**
 * 数据集重复检测服务
 * <p>
 * 自适应策略：
 * - ≤10K 条：精确 3-gram Jaccard + 并查集 O(n²)，毫秒级
 * - >10K 条：MinHash 签名 + LSH 分桶 O(n)，秒级
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DuplicateDetectionService {

    private final EvalDatasetItemMapper datasetItemMapper;

    /** 大数据集分界线：>3000 条时精确 O(n²) 已过慢，改用 MinHash LSH */
    private static final int LSH_THRESHOLD = 3000;
    /** MinHash 签名长度 */
    private static final int NUM_PERMS = 128;
    /** LSH 分 band 数（b × r = NUM_PERMS） */
    private static final int NUM_BANDS = 16;
    /** 每 band 行数 */
    private static final int ROWS_PER_BAND = NUM_PERMS / NUM_BANDS; // 8

    /** 每个排列的独立盐值（避免不同排列产生相关性） */
    private static final long[] PERM_SALTS = generatePermSalts(NUM_PERMS);

    /** 生成每个排列的独立盐值 */
    private static long[] generatePermSalts(int k) {
        long[] salts = new long[k];
        long seed = 0xFEDCBA9876543210L;
        for (int i = 0; i < k; i++) {
            seed = xorShift64(seed);
            salts[i] = seed;
        }
        return salts;
    }

    private static long xorShift64(long seed) {
        seed ^= seed << 13;
        seed ^= seed >> 7;
        seed ^= seed << 17;
        return seed;
    }

    // ======================== 主入口 ========================

    public DuplicateDetectionResult detect(Long datasetId, String fieldName, double threshold) {
        return detect(datasetId, fieldName, threshold, null);
    }

    /**
     * 主入口（带进度回调，供异步任务上报进度）
     *
     * @param progress 进度回调：参数为 (完成百分比 0-100, 阶段描述)，可为 null（同步场景）
     */
    public DuplicateDetectionResult detect(Long datasetId, String fieldName, double threshold,
                                           BiConsumer<Integer, String> progress) {
        if (progress != null) progress.accept(5, "正在加载数据...");
        List<EvalDatasetItem> items = datasetItemMapper.selectList(
                new LambdaQueryWrapper<EvalDatasetItem>()
                        .eq(EvalDatasetItem::getDatasetId, datasetId)
                        .orderByAsc(EvalDatasetItem::getSeqNo));

        DuplicateDetectionResult result = new DuplicateDetectionResult();
        result.setTotalItems(items.size());
        result.setFieldName(fieldName);
        result.setThreshold(threshold);

        if (progress != null) progress.accept(12, "已加载 " + items.size() + " 条数据");

        if (items.size() < 2) {
            if (progress != null) progress.accept(100, "数据量不足 2 条，无需检测");
            result.setGroups(List.of());
            result.setDuplicateCount(0);
            return result;
        }

        // 提取比对文本
        String[] texts = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            texts[i] = extractField(items.get(i), fieldName);
        }
        if (progress != null) progress.accept(15, "开始计算相似度...");

        // 自适应选择算法
        List<int[]> candidatePairs;
        if (items.size() <= LSH_THRESHOLD) {
            log.info("数据集 {} 条 ≤ {}，使用精确 Jaccard 检测", items.size(), LSH_THRESHOLD);
            candidatePairs = exactDetect(texts, threshold, progress);
        } else {
            log.info("数据集 {} 条 > {}，使用 MinHash LSH 检测", items.size(), LSH_THRESHOLD);
            candidatePairs = lshDetect(texts, threshold, progress);
        }
        if (progress != null) progress.accept(50, "正在分组重复数据...");

        // 并查集分组
        int n = items.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        for (int[] pair : candidatePairs) {
            union(parent, pair[0], pair[1]);
        }

        // 按组聚类
        Map<Integer, List<Integer>> groupMap = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            groupMap.computeIfAbsent(root, k -> new ArrayList<>()).add(i);
        }

        // 构建结果
        List<DuplicateGroup> groups = new ArrayList<>();
        int gDone = 0;
        int gTotal = (int) groupMap.values().stream().filter(l -> l.size() >= 2).count();
        for (List<Integer> groupIndices : groupMap.values()) {
            if (groupIndices.size() < 2) continue;

            DuplicateGroup group = new DuplicateGroup();
            List<GroupItem> groupItems = new ArrayList<>();
            double maxSim = 0;
            int refIdx = groupIndices.get(0);

            for (int idx : groupIndices) {
                GroupItem gi = new GroupItem();
                EvalDatasetItem item = items.get(idx);
                gi.setId(item.getId());
                gi.setSeqNo(item.getSeqNo());
                gi.setQuestion(item.getQuestion());
                gi.setReferenceAnswer(item.getReferenceAnswer());
                gi.setContext(item.getContext());
                gi.setCategory(item.getCategory());
                gi.setFieldValue(texts[idx] != null ? texts[idx] : "");

                if (idx != refIdx) {
                    double sim = jaccardSimilarity(texts[refIdx], texts[idx]);
                    gi.setSimilarity(Math.round(sim * 100.0) / 100.0);
                    maxSim = Math.max(maxSim, sim);
                } else {
                    gi.setSimilarity(1.0);
                }
                groupItems.add(gi);
            }

            // 补算组内最大相似度
            for (int a = 0; a < groupIndices.size(); a++) {
                for (int b = a + 1; b < groupIndices.size(); b++) {
                    double s = jaccardSimilarity(texts[groupIndices.get(a)], texts[groupIndices.get(b)]);
                    maxSim = Math.max(maxSim, s);
                }
            }

            group.setItems(groupItems);
            group.setMaxSimilarity(Math.round(maxSim * 100.0) / 100.0);
            groups.add(group);

            if (progress != null) {
                gDone++;
                if (gDone % 50 == 0 || gDone == gTotal) {
                    progress.accept(50 + (int) (30.0 * gDone / gTotal), "正在整理结果 " + gDone + "/" + gTotal + " 组");
                }
            }
        }

        groups.sort((a, b) -> b.getItems().size() - a.getItems().size());
        if (progress != null) progress.accept(90, "检测完成，正在汇总...");

        result.setGroups(groups);
        result.setDuplicateCount(groups.stream().mapToInt(g -> g.getItems().size()).sum());
        log.info("重复检测完成: datasetId={}, field={}, threshold={}, groups={}, duplicates={}",
                datasetId, fieldName, threshold, groups.size(), result.getDuplicateCount());
        return result;
    }

    // ======================== 精确检测 O(n²) ========================

    private List<int[]> exactDetect(String[] texts, double threshold, BiConsumer<Integer, String> progress) {
        int n = texts.length;
        List<int[]> pairs = new ArrayList<>();

        // 预计算每条文本的 3-gram 集合与长度：避免 O(n²) 内重复构建集合
        @SuppressWarnings("unchecked")
        Set<String>[] gramSets = new HashSet[n];
        int[] lens = new int[n];
        for (int i = 0; i < n; i++) {
            gramSets[i] = ngrams(texts[i], 3);
            lens[i] = texts[i] == null ? 0 : texts[i].length();
        }

        for (int i = 0; i < n; i++) {
            if (progress != null && (i % 200 == 0 || i == n - 1)) {
                progress.accept(15 + (int) (30.0 * i / n), "相似度计算中 " + i + "/" + n);
            }
            Set<String> ga = gramSets[i];
            if (ga.isEmpty()) continue;
            for (int j = i + 1; j < n; j++) {
                Set<String> gb = gramSets[j];
                if (gb.isEmpty()) continue;
                // 长度悬殊的两条文本，3-gram Jaccard 理论最大值 < 阈值，直接跳过
                int li = lens[i], lj = lens[j];
                if (li > 2 && lj > 2
                        && (double) Math.max(li, lj) / Math.min(li, lj) > 1.0 / threshold + 0.05) {
                    continue;
                }
                if (jaccardSets(ga, gb) >= threshold) {
                    pairs.add(new int[]{i, j});
                }
            }
        }
        return pairs;
    }

    // ======================== MinHash LSH 检测 ========================

    /**
     * MinHash + LSH 候选对检测
     * 1. 对每条文本生成 128 维 MinHash 签名
     * 2. 16 bands × 8 rows 分桶
     * 3. 同桶内任意两条作为候选对
     * 4. 对候选对计算精确 Jaccard 验证
     */
    private List<int[]> lshDetect(String[] texts, double threshold, BiConsumer<Integer, String> progress) {
        int n = texts.length;

        // 1. 生成 MinHash 签名：signatures[i][k] = 第 i 条文本的第 k 个 hash 值
        long[][] signatures = new long[n][NUM_PERMS];
        for (int i = 0; i < n; i++) {
            signatures[i] = minHashSignature(texts[i]);
            if (progress != null && (i % 500 == 0 || i == n - 1)) {
                progress.accept(15 + (int) (15.0 * i / n), "生成签名 " + i + "/" + n);
            }
        }

        // 2. LSH 分桶：对每个 band，将签名片段 hash 成桶 key，同桶内互为候选对
        Set<Long> candidateSet = new HashSet<>(); // 编码 (i,j) 的唯一 long
        List<int[]> candidates = new ArrayList<>();

        for (int band = 0; band < NUM_BANDS; band++) {
            if (progress != null) {
                progress.accept(30 + (int) (10.0 * band / NUM_BANDS), "分桶中 " + (band + 1) + "/" + NUM_BANDS);
            }
            // band 内：Map<桶hash, 条目索引列表>
            Map<Long, List<Integer>> buckets = new HashMap<>();
            int offset = band * ROWS_PER_BAND;

            for (int i = 0; i < n; i++) {
                long bucketHash = bandHash(signatures[i], offset, ROWS_PER_BAND);
                buckets.computeIfAbsent(bucketHash, k -> new ArrayList<>()).add(i);
            }

            // 同桶内两两配对
            for (List<Integer> bucket : buckets.values()) {
                if (bucket.size() < 2) continue;
                for (int a = 0; a < bucket.size(); a++) {
                    for (int b = a + 1; b < bucket.size(); b++) {
                        int i = bucket.get(a), j = bucket.get(b);
                        long code = pairCode(i, j);
                        if (candidateSet.add(code)) {
                            candidates.add(new int[]{i, j});
                        }
                    }
                }
            }
        }

        log.info("LSH 候选对: {} 条（vs 精确 {} 对）", candidates.size(), (long) n * (n - 1) / 2);

        // 3. 对候选对计算精确 Jaccard 验证
        List<int[]> confirmed = new ArrayList<>();
        int cTotal = Math.max(candidates.size(), 1);
        for (int c = 0; c < candidates.size(); c++) {
            if (progress != null && (c % 1000 == 0 || c == candidates.size() - 1)) {
                progress.accept(40 + (int) (10.0 * c / cTotal), "验证候选对 " + (c + 1) + "/" + candidates.size());
            }
            int[] pair = candidates.get(c);
            if (jaccardSimilarity(texts[pair[0]], texts[pair[1]]) >= threshold) {
                confirmed.add(pair);
            }
        }

        return confirmed;
    }

    /**
     * 生成文本的 MinHash 签名（128 维）
     * 基于 xorshift64 的独立哈希函数族，无溢出，O(1) per perm
     */
    private long[] minHashSignature(String text) {
        long[] sig = new long[NUM_PERMS];
        Arrays.fill(sig, Long.MAX_VALUE);

        if (text == null || text.length() < 3) return sig;

        Set<String> shingles = ngrams(text, 3);
        for (String s : shingles) {
            long h = fnv1a64(s);
            for (int k = 0; k < NUM_PERMS; k++) {
                long val = mixHash(h, PERM_SALTS[k]);
                if (val < sig[k]) {
                    sig[k] = val;
                }
            }
        }
        return sig;
    }

    /** FNV-1a 64-bit 哈希 */
    private long fnv1a64(String s) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /** 基于 xorshift64 的独立哈希函数：h' = mix(h ⊕ salt)，无溢出，O(1) */
    private static long mixHash(long h, long salt) {
        long x = h ^ salt;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        return x;
    }

    /**
     * LSH band hash：将 band 内 r 行签名组合成一个桶 key
     */
    private long bandHash(long[] signature, int offset, int rows) {
        long hash = 0;
        for (int i = 0; i < rows; i++) {
            // 用带盐值的混合函数
            long val = signature[offset + i];
            hash ^= (val * 0xbf58476d1ce4e5b9L) + 0x94d049bb133111ebL;
            hash = Long.rotateLeft(hash, 31);
        }
        return hash;
    }

    /**
     * 无损编码 pair (i, j) 为 long（i < j）
     * 适用于 i, j < 2^31
     */
    private long pairCode(int i, int j) {
        if (i > j) { int t = i; i = j; j = t; }
        return ((long) i << 32) | (j & 0xFFFFFFFFL);
    }

    // ======================== 公共工具 ========================

    private String extractField(EvalDatasetItem item, String fieldName) {
        if (item == null) return "";
        return switch (fieldName) {
            case "question" -> normalize(item.getQuestion());
            case "referenceAnswer" -> normalize(item.getReferenceAnswer());
            case "context" -> normalize(item.getContext());
            case "category" -> normalize(item.getCategory());
            // 其它角色（如 MODEL_RESPONSE 的模型回答）存在 extra_fields JSON 里，按原始列名取值
            default -> normalize(extraField(item, fieldName));
        };
    }

    /** 从 extra_fields JSON 中按原始列名提取值（model_response 等自定义/高维字段） */
    private String extraField(EvalDatasetItem item, String fieldName) {
        if (item.getExtraFields() == null || item.getExtraFields().isBlank()) return null;
        try {
            return cn.hutool.json.JSONUtil.parseObj(item.getExtraFields()).getStr(fieldName);
        } catch (Exception e) {
            return null;
        }
    }

    private String normalize(String text) {
        if (text == null || text.isBlank()) return "";
        return text.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    /** 基于预构建 gram 集合的 Jaccard（复用已构建的 Set，避免每次比较重复构建） */
    private double jaccardSets(Set<String> a, Set<String> b) {
        if (a == b) return 1.0;
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> large = a.size() <= b.size() ? b : a;
        int intersection = 0;
        for (String s : small) {
            if (large.contains(s)) intersection++;
        }
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0 : (double) intersection / union;
    }

    /** 字符 3-gram Jaccard 相似度（精确计算） */
    private double jaccardSimilarity(String a, String b) {
        if (a == null || b == null) return 0;
        if (a.equals(b)) return 1.0;
        if (a.length() < 3 || b.length() < 3) return a.equals(b) ? 1.0 : 0;

        Set<String> ngramsA = ngrams(a, 3);
        Set<String> ngramsB = ngrams(b, 3);

        if (ngramsA.isEmpty() || ngramsB.isEmpty()) return 0;

        int intersection = 0;
        for (String ngram : ngramsA) {
            if (ngramsB.contains(ngram)) intersection++;
        }
        int union = ngramsA.size() + ngramsB.size() - intersection;
        return union == 0 ? 0 : (double) intersection / union;
    }

    private Set<String> ngrams(String text, int n) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i <= text.length() - n; i++) {
            result.add(text.substring(i, i + n));
        }
        return result;
    }

    private int find(int[] parent, int x) {
        if (parent[x] != x) parent[x] = find(parent, parent[x]);
        return parent[x];
    }

    private void union(int[] parent, int x, int y) {
        int rx = find(parent, x), ry = find(parent, y);
        if (rx != ry) parent[rx] = ry;
    }
}
