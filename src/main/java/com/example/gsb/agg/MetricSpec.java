package com.example.gsb.agg;

import java.util.Objects;

/**
 * 聚合指标定义：指标类型 + 度量字段名。
 *
 * <p>{@link MetricType#COUNT} 不依赖度量字段；其余类型必须指定字段名。
 * 指标名称默认由类型与字段名派生（如 {@code sum_amount}），也可自定义。
 */
public final class MetricSpec {

    private final String name;
    private final MetricType type;
    private final String field;

    private MetricSpec(String name, MetricType type, String field) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.field = field;
    }

    /** 计数指标，指标名为 {@code count}。 */
    public static MetricSpec count() {
        return new MetricSpec("count", MetricType.COUNT, null);
    }

    /** 求和指标，指标名为 {@code sum_<field>}。 */
    public static MetricSpec sum(String field) {
        return of(MetricType.SUM, field);
    }

    /** 平均值指标，指标名为 {@code avg_<field>}。 */
    public static MetricSpec avg(String field) {
        return of(MetricType.AVG, field);
    }

    /** 最大值指标，指标名为 {@code max_<field>}。 */
    public static MetricSpec max(String field) {
        return of(MetricType.MAX, field);
    }

    /** 按类型与字段构造指标，指标名自动派生。 */
    public static MetricSpec of(MetricType type, String field) {
        Objects.requireNonNull(type, "type");
        if (type == MetricType.COUNT) {
            return count();
        }
        Objects.requireNonNull(field, "field");
        return new MetricSpec(type.name().toLowerCase() + "_" + field, type, field);
    }

    /** 以自定义名称构造指标。 */
    public MetricSpec named(String customName) {
        return new MetricSpec(customName, type, field);
    }

    public String getName() {
        return name;
    }

    public MetricType getType() {
        return type;
    }

    /** 度量字段名；COUNT 指标返回 {@code null}。 */
    public String getField() {
        return field;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MetricSpec other)) {
            return false;
        }
        return name.equals(other.name) && type == other.type && Objects.equals(field, other.field);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, field);
    }

    @Override
    public String toString() {
        return field == null ? name : name + "(" + field + ")";
    }
}
