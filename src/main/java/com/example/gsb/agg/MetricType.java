package com.example.gsb.agg;

/**
 * 支持的聚合指标类型。
 */
public enum MetricType {
    /** 分组内事件条数，不需要度量字段。 */
    COUNT,
    /** 度量字段求和。 */
    SUM,
    /** 度量字段平均值（以非空度量值为准）。 */
    AVG,
    /** 度量字段最大值。 */
    MAX
}
