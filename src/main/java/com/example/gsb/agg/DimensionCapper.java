package com.example.gsb.agg;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 维度基数控制器：保留先出现的 {@code limit} 个不同取值，之后出现的取值统一折叠到
 * {@link MultiDimAggregator#OTHER_VALUE} 合并桶。
 *
 * <p>流式处理、状态 O(limit)：无论该维度后续出现多少新取值，该维度参与分组的取值数都被
 * 钳制在 limit + 1 以内（+1 为合并桶），因此也天然满足“增量更新时不重算全部”。
 *
 * <p>统计恒等式：distinctValues = retainedValues + mergedValues。
 */
final class DimensionCapper {

    private final String dimension;
    private final int limit;
    private final Map<String, String> retained = new LinkedHashMap<>();
    private int mergedValues;

    DimensionCapper(String dimension, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("cardinality limit must be positive: " + limit);
        }
        this.dimension = dimension;
        this.limit = limit;
    }

    /** 把原始取值映射为实际参与分组的取值。 */
    String map(String rawValue) {
        String existing = retained.get(rawValue);
        if (existing != null) {
            return existing;
        }
        if (retained.size() < limit) {
            retained.put(rawValue, rawValue);
            return rawValue;
        }
        mergedValues++;
        return MultiDimAggregator.OTHER_VALUE;
    }

    int distinctValues() {
        return retained.size() + mergedValues;
    }

    CardinalityStats snapshot() {
        return new CardinalityStats(limit,
                retained.size() + mergedValues,
                retained.size(),
                mergedValues,
                mergedValues);
    }

    String dimension() {
        return dimension;
    }
}
