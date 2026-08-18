package com.eval.common.util;

import java.util.List;

/**
 * Kappa 一致性系数计算工具
 * - Cohen's Kappa：两个标注者之间的一致性（扣除偶然一致）
 * - Fleiss' Kappa：多个标注者在多个样本上的整体一致性
 *
 * 类别：0 = 非badcase(goodcase), 1 = badcase
 */
public class KappaCalculator {

    /**
     * Cohen's Kappa：两个标注者之间的一致性
     * @param r1 标注者1 的判定列表（与 r2 等长，一一对应同一样本）
     * @param r2 标注者2 的判定列表
     * @return Kappa 值 [-1, 1]，样本不足或一方全相同返回 NaN
     */
    public static double cohenKappa(List<Integer> r1, List<Integer> r2) {
        if (r1 == null || r2 == null || r1.size() != r2.size() || r1.isEmpty()) {
            return Double.NaN;
        }
        int n = r1.size();

        // 统计 2x2 混淆矩阵
        // a: 两人都判1, b: A判1且B判0, c: A判0且B判1, d: 两人都判0
        int a = 0, b = 0, c = 0, d = 0;
        for (int i = 0; i < n; i++) {
            int v1 = r1.get(i) == null ? 0 : r1.get(i);
            int v2 = r2.get(i) == null ? 0 : r2.get(i);
            if (v1 == 1 && v2 == 1) a++;
            else if (v1 == 1 && v2 == 0) b++;
            else if (v1 == 0 && v2 == 1) c++;
            else d++;
        }

        // 观察一致率 Po
        double po = (a + d) * 1.0 / n;

        // 期望一致率 Pe
        // A判1的比例 = (a+b)/n, B判1的比例 = (a+c)/n
        // A判0的比例 = (c+d)/n, B判0的比例 = (b+d)/n
        double pe = ((a + b) * 1.0 / n) * ((a + c) * 1.0 / n)
                + ((c + d) * 1.0 / n) * ((b + d) * 1.0 / n);

        // 若一方全相同（Pe = 1），Kappa 无定义
        if (pe >= 1.0 - 1e-9) {
            return Double.NaN;
        }
        return (po - pe) / (1 - pe);
    }

    /**
     * Fleiss' Kappa：多个标注者在多个样本上的整体一致性
     * @param matrix 矩阵 [样本数][类别数]：matrix[i][k] = 第i个样本被判为类别k 的人数
     * @return Kappa 值 [-1, 1]，样本不足或无方差返回 NaN
     */
    public static double fleissKappa(int[][] matrix) {
        if (matrix == null || matrix.length == 0) {
            return Double.NaN;
        }
        int N = matrix.length;       // 样本数
        int K = matrix[0].length;    // 类别数（这里 = 2: good/bad）

        // 每个样本的总标注人数 n（假设每个样本标注人数相同）
        int n = 0;
        for (int k = 0; k < K; k++) n += matrix[0][k];
        if (n < 2) return Double.NaN; // 每个样本至少需要2个标注者

        // p_k: 第k个类别在所有标注中的总比例
        double[] p = new double[K];
        double totalJudgments = 0;
        for (int i = 0; i < N; i++) {
            for (int k = 0; k < K; k++) {
                p[k] += matrix[i][k];
                totalJudgments += matrix[i][k];
            }
        }
        for (int k = 0; k < K; k++) p[k] /= totalJudgments;

        // P_i: 第i个样本上，两个随机标注者一致的概率
        // P_i = (sum(n_ik^2) - n) / (n*(n-1))
        double sumP = 0;
        for (int i = 0; i < N; i++) {
            long sumSq = 0;
            for (int k = 0; k < K; k++) sumSq += (long) matrix[i][k] * matrix[i][k];
            double Pi = (sumSq - n) * 1.0 / (n * (n - 1));
            sumP += Pi;
        }
        double Pbar = sumP / N;  // 观察一致率

        // Pe: 期望一致率 = sum(p_k^2)
        double Pe = 0;
        for (int k = 0; k < K; k++) Pe += p[k] * p[k];

        if (Pe >= 1.0 - 1e-9) return Double.NaN;
        return (Pbar - Pe) / (1 - Pe);
    }

    /**
     * Kappa 档位描述（Landis & Koch 标准）
     */
    public static String kappaLevel(double kappa) {
        if (Double.isNaN(kappa)) return "样本不足";
        if (kappa < 0) return "比偶然更差";
        if (kappa < 0.2) return "极低一致";
        if (kappa < 0.4) return "一般一致";
        if (kappa < 0.6) return "中等一致";
        if (kappa < 0.8) return "良好一致";
        return "几乎完美";
    }
}
