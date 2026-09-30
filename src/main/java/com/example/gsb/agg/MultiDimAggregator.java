package com.example.gsb.agg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 多维聚合组件。
 *
 * <p>一个聚合器内可同时维护多个“视图”（维度子集组合，例如 {@code [省]}、{@code [省, 城市]}、
 * {@code [城市, 渠道]}），每条数据到达时在每个视图中只更新它落入的那一个分组，
 * 其余分组不动，即增量更新。
 *
 * <p>每个视图同时维护已配置的全部指标（{@link MetricType}）。维度可通过
 * {@link #builder()} 配置基数上限，超出上限的取值折叠到 {@link #OTHER_VALUE} 合并桶。
 *
 * <p>本类为非线程安全实现，单线程写入；查询返回的均为快照对象。
 */
public final class MultiDimAggregator {

    /** 维度取值为 {@code null} 时规范化后的占位值。 */
    public static final String NULL_VALUE = "__NULL__";

    /** 触发基数上限后，超出的取值统一折叠进的合并桶。 */
    public static final String OTHER_VALUE = "__OTHER__";

    private final Set<MetricType> metrics;
    private final List<List<String>> views;
    private final Map<String, DimensionCapper> cappers;

    private final List<Map<AggregationKey, MutableAggregate>> groupTables;
    private final Map<String, Integer> groupCounts;
    private long metricComputations;
    private long incrementalUpdates;

    private MultiDimAggregator(Builder builder) {
        this.metrics = Set.copyOf(builder.metrics);
        if (this.metrics.isEmpty()) {
            throw new IllegalStateException("at least one metric must be configured");
        }
        if (builder.views.isEmpty()) {
            throw new IllegalStateException("at least one view (dimension subset) must be configured");
        }
        this.views = new ArrayList<>();
        this.cappers = new HashMap<>();
        this.groupTables = new ArrayList<>();
        this.groupCounts = new LinkedHashMap<>();
        for (List<String> view : builder.views) {
            List<String> dims = List.copyOf(view);
            this.views.add(dims);
            this.groupTables.add(new HashMap<>());
            this.groupCounts.put(String.join(",", dims), 0);
            for (String dim : dims) {
                this.cappers.computeIfAbsent(dim, d -> {
                    Integer limit = builder.cardinalityLimits.get(d);
                    return new DimensionCapper(d, limit == null ? Integer.MAX_VALUE : limit);
                });
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 增量并入一条数据：仅更新各视图中该数据落入的分组，不触碰其他分组。
     *
     * @return 该数据触达的分组总数（每个视图恰好一个）
     */
    public int addEvent(Event event) {
        Objects.requireNonNull(event, "event must not be null");
        // 同一条数据在每个维度上只做一次基数控制，再分发给各视图，
        // 保证多视图共享同一维度时折叠与统计不被重复计数。
        Map<String, String> cappedValues = new HashMap<>(cappers.size());
        for (Map.Entry<String, DimensionCapper> entry : cappers.entrySet()) {
            String raw = event.dimensions().get(entry.getKey());
            cappedValues.put(entry.getKey(),
                    entry.getValue().map(raw == null ? NULL_VALUE : raw));
        }
        int touched = 0;
        for (int v = 0; v < views.size(); v++) {
            List<String> dims = views.get(v);
            List<String> parts = new ArrayList<>(dims.size());
            for (String dim : dims) {
                parts.add(cappedValues.get(dim));
            }
            AggregationKey key = new AggregationKey(parts);
            MutableAggregate aggregate = groupTables.get(v)
                    .computeIfAbsent(key, k -> new MutableAggregate());
            boolean created = aggregate.count() == 0;
            aggregate.add(event.measure());
            if (created) {
                String name = String.join(",", dims);
                groupCounts.put(name, groupCounts.get(name) + 1);
            }
            touched++;
        }
        incrementalUpdates++;
        metricComputations += (long) touched * metrics.size();
        return touched;
    }

    /** 批量增量并入，等价于逐条调用 {@link #addEvent(Event)}。 */
    public int addEvents(Iterable<Event> events) {
        Objects.requireNonNull(events, "events must not be null");
        int touched = 0;
        for (Event event : events) {
            touched += addEvent(event);
        }
        return touched;
    }

    /** 已注册的全部视图（每个视图为有序维度列表）。 */
    public List<List<String>> views() {
        return Collections.unmodifiableList(views);
    }

    /**
     * 按分组键查询指标值。{@code keyValues} 的顺序与数量必须与视图维度一致；
     * 元素允许为 {@code null}（等价于 {@link #NULL_VALUE}）。
     * 分组不存在时返回 {@code found=false} 的 {@link GroupResult}，不抛异常。
     */
    public GroupResult query(List<String> viewDimensions, String... keyValues) {
        int index = requireView(viewDimensions);
        return query(index, Arrays.asList(keyValues));
    }

    /** 按“维度 → 取值”映射查询，缺失的维度按 {@link #NULL_VALUE} 处理。 */
    public GroupResult query(Map<String, String> key, List<String> viewDimensions) {
        Objects.requireNonNull(key, "key must not be null");
        int index = requireView(viewDimensions);
        List<String> parts = new ArrayList<>(viewDimensions.size());
        for (String dim : viewDimensions) {
            String value = key.get(dim);
            parts.add(value == null ? NULL_VALUE : value);
        }
        return query(index, parts);
    }

    private GroupResult query(int viewIndex, List<String> keyValues) {
        List<String> dims = views.get(viewIndex);
        if (keyValues.size() != dims.size()) {
            throw new IllegalArgumentException(
                    "key has " + keyValues.size() + " values but view has " + dims.size() + " dimensions");
        }
        List<String> normalized = new ArrayList<>(keyValues.size());
        for (String value : keyValues) {
            normalized.add(value == null ? NULL_VALUE : value);
        }
        AggregationKey key = new AggregationKey(normalized);
        MutableAggregate aggregate = groupTables.get(viewIndex).get(key);
        List<String> viewCopy = List.copyOf(dims);
        if (aggregate == null) {
            return new GroupResult(viewCopy, key, false, null);
        }
        return new GroupResult(viewCopy, key, true, aggregate.snapshot(metrics));
    }

    /**
     * 视图内全局 TopN：按指定指标降序取前 N；指标相同按分组键升序保证稳定结果。
     * 分组数不足 N 时返回全部分组。
     */
    public List<GroupResult> topN(List<String> viewDimensions, MetricType metric, int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be positive: " + n);
        }
        int index = requireView(viewDimensions);
        Objects.requireNonNull(metric, "metric must not be null");
        return collect(index, metric, n, groupTables.get(index).keySet());
    }

    /**
     * 组合 TopN：在子视图中，按父视图取值圈定的“每一个父分组”内分别取前 N。
     * 子视图维度必须以父视图维度为有序前缀（例如父=[省]，子=[省, 城市]）。
     *
     * @param parentDimensions 父分组视图
     * @param childDimensions  子分组视图
     * @param metric           排序指标
     * @param n                每个父分组返回的最大条数，不足时按实际返回
     * @return 父分组键 → 该父分组内 TopN 结果；只包含数据中实际出现过的父分组
     */
    public Map<AggregationKey, List<GroupResult>> topNWithin(List<String> parentDimensions,
                                                             List<String> childDimensions,
                                                             MetricType metric,
                                                             int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be positive: " + n);
        }
        Objects.requireNonNull(metric, "metric must not be null");
        int parentIndex = requireView(parentDimensions);
        int childIndex = requireView(childDimensions);
        if (childIndex == parentIndex || !isPrefix(views.get(parentIndex), views.get(childIndex))) {
            throw new IllegalArgumentException(
                    "child view " + views.get(childIndex) + " must extend parent view " + views.get(parentIndex));
        }
        Map<AggregationKey, MutableAggregate> childTable = groupTables.get(childIndex);
        int prefixLen = views.get(parentIndex).size();
        Map<AggregationKey, List<AggregationKey>> grouped = new LinkedHashMap<>();
        for (AggregationKey childKey : childTable.keySet()) {
            AggregationKey parentKey = new AggregationKey(childKey.values().subList(0, prefixLen));
            grouped.computeIfAbsent(parentKey, k -> new ArrayList<>()).add(childKey);
        }
        Map<AggregationKey, List<GroupResult>> result = new LinkedHashMap<>();
        for (Map.Entry<AggregationKey, List<AggregationKey>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), collect(childIndex, metric, n, entry.getValue()));
        }
        return result;
    }

    /** 某视图的全部分组快照，按分组键升序返回。 */
    public List<GroupResult> groups(List<String> viewDimensions) {
        int index = requireView(viewDimensions);
        List<AggregationKey> keys = new ArrayList<>(groupTables.get(index).keySet());
        Collections.sort(keys);
        return collect(index, null, Integer.MAX_VALUE, keys);
    }

    /** 运行统计快照。 */
    public AggregatorStats stats() {
        Map<String, Integer> counts = new LinkedHashMap<>(groupCounts);
        Map<String, CardinalityStats> card = new LinkedHashMap<>();
        for (Map.Entry<String, DimensionCapper> entry : cappers.entrySet()) {
            card.put(entry.getKey(), entry.getValue().snapshot());
        }
        return new AggregatorStats(Collections.unmodifiableMap(counts),
                metricComputations, incrementalUpdates, Collections.unmodifiableMap(card));
    }

    /** 单个维度的基数控制统计。 */
    public CardinalityStats cardinalityOf(String dimension) {
        DimensionCapper capper = cappers.get(dimension);
        if (capper == null) {
            throw new IllegalArgumentException("unknown dimension: " + dimension);
        }
        return capper.snapshot();
    }

    private List<GroupResult> collect(int viewIndex, MetricType metric, int n, Iterable<AggregationKey> keys) {
        Map<AggregationKey, MutableAggregate> table = groupTables.get(viewIndex);
        List<GroupResult> results = new ArrayList<>();
        for (AggregationKey key : keys) {
            MutableAggregate aggregate = table.get(key);
            if (aggregate != null) {
                results.add(new GroupResult(List.copyOf(views.get(viewIndex)),
                        key, true, aggregate.snapshot(metrics)));
            }
        }
        Comparator<GroupResult> comparator = Comparator.comparing(GroupResult::key);
        if (metric != null) {
            comparator = Comparator
                    .comparingDouble((GroupResult r) -> r.metrics().value(metric)).reversed()
                    .thenComparing(GroupResult::key);
        }
        results.sort(comparator);
        if (results.size() > n) {
            return new ArrayList<>(results.subList(0, n));
        }
        return results;
    }

    private int requireView(List<String> viewDimensions) {
        Objects.requireNonNull(viewDimensions, "viewDimensions must not be null");
        List<String> wanted = List.copyOf(viewDimensions);
        for (int i = 0; i < views.size(); i++) {
            if (views.get(i).equals(wanted)) {
                return i;
            }
        }
        throw new IllegalArgumentException("view not registered: " + wanted);
    }

    private static boolean isPrefix(List<String> prefix, List<String> values) {
        if (prefix.size() >= values.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!prefix.get(i).equals(values.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** {@link MultiDimAggregator} 构建器。 */
    public static final class Builder {

        private final List<List<String>> views = new ArrayList<>();
        private final Set<MetricType> metrics = new java.util.HashSet<>();
        private final Map<String, Integer> cardinalityLimits = new HashMap<>();

        private Builder() {
        }

        /** 注册一个视图（维度子集），维度顺序即分组键顺序。重复注册会被忽略。 */
        public Builder view(String... dimensions) {
            Objects.requireNonNull(dimensions, "dimensions must not be null");
            List<String> dims = new ArrayList<>();
            for (String dim : dimensions) {
                if (dim == null || dim.isBlank()) {
                    throw new IllegalArgumentException("dimension name must not be blank");
                }
                if (dims.contains(dim)) {
                    throw new IllegalArgumentException("duplicate dimension in view: " + dim);
                }
                dims.add(dim);
            }
            if (dims.isEmpty()) {
                throw new IllegalArgumentException("view must contain at least one dimension");
            }
            if (!views.contains(dims)) {
                views.add(List.copyOf(dims));
            }
            return this;
        }

        /** 声明需要计算的指标，可多次调用。 */
        public Builder metric(MetricType... types) {
            for (MetricType type : types) {
                metrics.add(Objects.requireNonNull(type, "metric must not be null"));
            }
            return this;
        }

        /** 声明需要计算的指标集合。 */
        public Builder metrics(Set<MetricType> types) {
            metrics.addAll(Objects.requireNonNull(types, "types must not be null"));
            return this;
        }

        /** 限制某维度最多保留 {@code limit} 个独立取值，超出部分折叠进合并桶。 */
        public Builder cardinalityLimit(String dimension, int limit) {
            if (dimension == null || dimension.isBlank()) {
                throw new IllegalArgumentException("dimension name must not be blank");
            }
            if (limit <= 0) {
                throw new IllegalArgumentException("cardinality limit must be positive: " + limit);
            }
            cardinalityLimits.put(dimension, limit);
            return this;
        }

        public MultiDimAggregator build() {
            return new MultiDimAggregator(this);
        }
    }
}
