package com.example.gsb.agg;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 聚合器的运行统计快照。
 *
 * @param groupCountBySpec     各方案当前分组数（含被合并形成的 {@code __OTHER__} 桶）
 * @param mergedGroupsBySpec   各方案因基数限制被合并掉的分组取值数（按维度累计）
 * @param metricEvaluations    累计指标累加次数（每条数据对每个方案的每个指标计一次）
 * @param incrementalUpdates   累计增量更新次数（成功处理的事件条数）
 */
public record AggregationStats(
        Map<String, Integer> groupCountBySpec,
        Map<String, Long> mergedGroupsBySpec,
        long metricEvaluations,
        long incrementalUpdates) {

    public AggregationStats {
        groupCountBySpec = Collections.unmodifiableMap(new LinkedHashMap<>(groupCountBySpec));
        mergedGroupsBySpec = Collections.unmodifiableMap(new LinkedHashMap<>(mergedGroupsBySpec));
    }

    /** 所有方案的分组总数。 */
    public int totalGroupCount() {
        return groupCountBySpec.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** 所有方案被合并的分组取值总数。 */
    public long totalMergedGroups() {
        return mergedGroupsBySpec.values().stream().mapToLong(Long::longValue).sum();
    }
}
