package com.example.gsb.agg;

import java.util.Map;

/**
 * 聚合器运行统计（不可变快照）。
 *
 * @param groupCount            各视图当前分组数（含合并桶分组）
 * @param metricComputations    指标计算次数：每条数据在每个视图的受影响分组上，
 *                              对每个已配置指标计一次，即 Σ(受影响分组数 × 指标数)
 * @param incrementalUpdates    增量更新次数：成功并入的数据条数（每条数据仅 touch 受影响分组）
 * @param cardinality           各维度的基数控制统计
 */
public record AggregatorStats(Map<String, Integer> groupCount,
                              long metricComputations,
                              long incrementalUpdates,
                              Map<String, CardinalityStats> cardinality) {
}
