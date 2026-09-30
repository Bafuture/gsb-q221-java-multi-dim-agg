package com.example.gsb.agg;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单个分组的查询/TopN 结果：维度取值与指标值。
 */
public record GroupResult(Map<String, String> dimensions, MetricValues metrics) {

    public GroupResult {
        dimensions = Map.copyOf(dimensions);
        Objects.requireNonNull(metrics, "metrics");
    }

    static GroupResult of(GroupBySpec spec, GroupKey key, MetricValues metrics) {
        Map<String, String> dims = new LinkedHashMap<>();
        List<String> dimNames = spec.getDimensions();
        for (int i = 0; i < dimNames.size(); i++) {
            dims.put(dimNames.get(i), key.get(i));
        }
        return new GroupResult(dims, metrics);
    }

    /** 直接取某指标值。 */
    public double metric(String metricName) {
        return metrics.get(metricName);
    }
}
