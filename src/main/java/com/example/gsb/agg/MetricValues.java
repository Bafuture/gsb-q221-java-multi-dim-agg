package com.example.gsb.agg;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 一个分组在某个时刻的全部指标结果。
 *
 * <p>未收到任何有效度量值的 AVG/MAX 指标其值为 {@link Double#NaN}，
 * SUM 为 0，COUNT 始终为非负数。
 */
public final class MetricValues {

    private final Map<String, Double> values;

    public MetricValues(Map<String, Double> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** 按指标名取值；指标不存在抛出 {@link IllegalArgumentException}。 */
    public double get(String metricName) {
        Double value = values.get(metricName);
        if (value == null) {
            throw new IllegalArgumentException("未知指标: " + metricName);
        }
        return value;
    }

    /** 按指标定义取值。 */
    public double get(MetricSpec metric) {
        return get(metric.getName());
    }

    public boolean contains(String metricName) {
        return values.containsKey(metricName);
    }

    /** 指标名 -> 指标值（有序不可变）。 */
    public Map<String, Double> asMap() {
        return values;
    }

    @Override
    public String toString() {
        return new TreeMap<>(values).toString();
    }
}
