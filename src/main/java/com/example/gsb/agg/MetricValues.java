package com.example.gsb.agg;

import java.util.Set;

/**
 * 某个分组在某一时刻的指标快照（不可变）。
 * 仅在视图配置中声明的指标为可读；读取未配置的指标会抛出 {@link IllegalStateException}。
 */
public final class MetricValues {

    private final Set<MetricType> configured;
    private final long count;
    private final double sum;
    private final double max;

    MetricValues(Set<MetricType> configured, long count, double sum, double max) {
        this.configured = Set.copyOf(configured);
        this.count = count;
        this.sum = sum;
        this.max = max;
    }

    public boolean contains(MetricType metric) {
        return configured.contains(metric);
    }

    public Set<MetricType> metrics() {
        return configured;
    }

    public long count() {
        ensure(MetricType.COUNT);
        return count;
    }

    public double sum() {
        ensure(MetricType.SUM);
        return sum;
    }

    public double avg() {
        ensure(MetricType.AVG);
        if (count == 0) {
            return Double.NaN;
        }
        return sum / count;
    }

    public double max() {
        ensure(MetricType.MAX);
        return max;
    }

    /** 读取任意指标的统一入口，便于按 {@link MetricType} 做 TopN 排序。 */
    public double value(MetricType metric) {
        return switch (metric) {
            case COUNT -> count();
            case SUM -> sum();
            case AVG -> avg();
            case MAX -> max();
        };
    }

    private void ensure(MetricType metric) {
        if (!configured.contains(metric)) {
            throw new IllegalStateException("metric " + metric + " is not configured for this view");
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("MetricValues{");
        boolean first = true;
        for (MetricType metric : MetricType.values()) {
            if (configured.contains(metric)) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(metric).append('=').append(value(metric));
                first = false;
            }
        }
        return sb.append('}').toString();
    }
}
