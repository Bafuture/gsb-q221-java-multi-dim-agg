package com.example.gsb.agg;

import java.util.List;

/**
 * 分组查询结果（不可变）。分组不存在时 {@link #found()} 为 {@code false}，
 * {@link #metrics()} 为 {@code null}，调用方可显式区分“无数据”与“指标为零”。
 *
 * @param viewDimensions 所属视图的维度列表
 * @param key            查询/命中的分组键
 * @param found          是否命中已有分组
 * @param metrics        命中时的指标快照；未命中时为 {@code null}
 */
public record GroupResult(List<String> viewDimensions,
                          AggregationKey key,
                          boolean found,
                          MetricValues metrics) {

    /** 未命中时的空指标读数，方便直接做默认值处理。 */
    public MetricValues metricsOrElse(MetricValues fallback) {
        return found ? metrics : fallback;
    }
}
