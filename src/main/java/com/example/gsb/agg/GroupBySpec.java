package com.example.gsb.agg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一个分组方案（维度组合 + 指标集合 + 可选的维度基数上限）。
 *
 * <p>同一个 {@link MultiDimAggregator} 上可以注册多个方案，
 * 例如「按省」「按省+城市」「按城市+渠道」，每条新数据会增量更新所有方案。
 */
public final class GroupBySpec {

    private final String name;
    private final List<String> dimensions;
    private final List<MetricSpec> metrics;
    private final Map<String, Integer> cardinalityLimits;

    private GroupBySpec(Builder builder) {
        if (builder.dimensions.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个分组维度");
        }
        if (builder.metrics.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个聚合指标");
        }
        this.name = Objects.requireNonNull(builder.name, "name");
        this.dimensions = List.copyOf(builder.dimensions);
        this.metrics = List.copyOf(builder.metrics);
        this.cardinalityLimits = Collections.unmodifiableMap(new LinkedHashMap<>(builder.cardinalityLimits));
    }

    /** 创建构建器，{@code name} 是该方案在聚合器中的唯一标识。 */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String getName() {
        return name;
    }

    /** 分组维度，顺序即分组键顺序。 */
    public List<String> getDimensions() {
        return dimensions;
    }

    public List<MetricSpec> getMetrics() {
        return metrics;
    }

    /** 维度名 -> 该维度允许的最大不同取值数；超出取值合并到 {@code __OTHER__} 桶。 */
    public Map<String, Integer> getCardinalityLimits() {
        return cardinalityLimits;
    }

    public static final class Builder {
        private final String name;
        private final List<String> dimensions = new ArrayList<>();
        private final List<MetricSpec> metrics = new ArrayList<>();
        private final Map<String, Integer> cardinalityLimits = new LinkedHashMap<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder dimension(String dimension) {
            Objects.requireNonNull(dimension, "dimension");
            if (dimensions.contains(dimension)) {
                throw new IllegalArgumentException("维度重复: " + dimension);
            }
            dimensions.add(dimension);
            return this;
        }

        public Builder dimensions(String... dims) {
            for (String dim : dims) {
                dimension(dim);
            }
            return this;
        }

        public Builder metric(MetricSpec metric) {
            Objects.requireNonNull(metric, "metric");
            for (MetricSpec existing : metrics) {
                if (existing.getName().equals(metric.getName())) {
                    throw new IllegalArgumentException("指标名重复: " + metric.getName());
                }
            }
            metrics.add(metric);
            return this;
        }

        public Builder metrics(MetricSpec... metricSpecs) {
            for (MetricSpec spec : metricSpecs) {
                metric(spec);
            }
            return this;
        }

        /**
         * 限制某维度的不同取值数：先出现的 {@code maxValues} 个取值各自保留，
         * 之后出现的取值统一合并到 {@code __OTHER__} 桶。
         */
        public Builder cardinalityLimit(String dimension, int maxValues) {
            Objects.requireNonNull(dimension, "dimension");
            if (maxValues <= 0) {
                throw new IllegalArgumentException("基数上限必须为正数: " + maxValues);
            }
            cardinalityLimits.put(dimension, maxValues);
            return this;
        }

        public GroupBySpec build() {
            return new GroupBySpec(this);
        }
    }
}
