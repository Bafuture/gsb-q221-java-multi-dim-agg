package com.example.gsb.agg;

import java.util.Set;

/**
 * 单个分组的可变累加状态。COUNT / SUM / AVG / MAX 都可由
 * count、sum、max 三个基础量在一次 O(1) 更新内同步维护，无需重算历史。
 */
final class MutableAggregate {

    private long count;
    private double sum;
    private double max = Double.NEGATIVE_INFINITY;

    /** 增量并入一条数据，所有已配置指标一次更新完成。 */
    void add(double measure) {
        count++;
        sum += measure;
        if (measure > max) {
            max = measure;
        }
    }

    long count() {
        return count;
    }

    MetricValues snapshot(Set<MetricType> configured) {
        return new MetricValues(configured, count, sum, max);
    }
}
