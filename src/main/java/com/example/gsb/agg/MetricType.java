package com.example.gsb.agg;

/**
 * 聚合指标类型。每个视图可同时配置若干种指标，对每条进入该视图的数据全部计算一次。
 */
public enum MetricType {
    /** 计数：分组内数据条数。 */
    COUNT,
    /** 求和：分组内度量值之和。 */
    SUM,
    /** 平均值：分组内度量值之和 / 计数。 */
    AVG,
    /** 最大值：分组内度量值的最大值。 */
    MAX
}
