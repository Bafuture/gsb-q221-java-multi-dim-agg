package com.example.gsb.agg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 多维聚合组件：支持任意维度子集分组、多指标并行计算、组合 TopN、
 * 维度基数控制、增量更新与按分组键查询。
 *
 * <p>使用方式：
 * <ol>
 *   <li>通过 {@link #register(GroupBySpec)} 注册一个或多个分组方案；</li>
 *   <li>每条新数据调用 {@link #addEvent(Map)}，只增量更新受影响的分组；</li>
 *   <li>用 {@link #query(String, Map)} 按分组键查询，用 {@link #topN(String, String, int)} 取 TopN；</li>
 *   <li>用 {@link #stats()} 查看分组数、指标计算次数与增量更新次数。</li>
 * </ol>
 *
 * <p>事件是一个字段表：维度字段取 {@link String#valueOf(Object)}，
 * 度量字段必须是 {@link Number}，缺失或非数值的度量字段不参与该指标的累加。
 */
public final class MultiDimAggregator {

    /** 维度基数超限时，溢出取值被合并到的桶名。 */
    public static final String OTHER_BUCKET = "__OTHER__";

    private final Map<String, SpecState> specs = new LinkedHashMap<>();
    private long metricEvaluations;
    private long incrementalUpdates;

    /** 注册分组方案；方案名必须唯一。 */
    public void register(GroupBySpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (specs.containsKey(spec.getName())) {
            throw new IllegalArgumentException("分组方案已存在: " + spec.getName());
        }
        specs.put(spec.getName(), new SpecState(spec));
    }

    /**
     * 增量处理一条新数据：对每个已注册方案，只定位并更新该数据所属的那一个分组。
     *
     * @return 本次更新影响的分组总数（各方案各计一个）
     */
    public int addEvent(Map<String, ?> event) {
        Objects.requireNonNull(event, "event");
        int touched = 0;
        for (SpecState state : specs.values()) {
            GroupKey key = state.toGroupKey(event);
            GroupState group = state.groups.computeIfAbsent(key, k -> new GroupState(state.metricCount()));
            state.accumulate(group, event);
            touched++;
        }
        incrementalUpdates++;
        return touched;
    }

    /** 批量增量处理。 */
    public void addAll(Iterable<? extends Map<String, ?>> events) {
        for (Map<String, ?> event : events) {
            addEvent(event);
        }
    }

    /**
     * 按分组键查询某个方案的指标结果。
     *
     * <p>{@code key} 必须恰好包含该方案的全部分组维度；查询值会先应用与写入时
     * 相同的基数合并映射（被合并的取值会命中 {@code __OTHER__} 桶）。
     *
     * @return 分组存在时返回其结果；不存在时返回 {@link Optional#empty()}
     * @throws IllegalArgumentException 方案不存在或查询键维度不匹配
     */
    public Optional<GroupResult> query(String specName, Map<String, String> key) {
        SpecState state = requireSpec(specName);
        GroupKey groupKey = state.toGroupKeyFromQuery(key);
        GroupState group = state.groups.get(groupKey);
        if (group == null) {
            return Optional.empty();
        }
        return Optional.of(GroupResult.of(state.spec, groupKey, state.snapshot(group)));
    }

    /**
     * 组合 TopN：按指定指标对某方案的全部分组降序取前 {@code n} 个，
     * 分组不足 {@code n} 时按实际数量返回。指标值相同按分组键字典序排列，
     * 无有效度量值（NaN）的分组排在最后。
     */
    public List<GroupResult> topN(String specName, String metricName, int n) {
        if (n < 1) {
            throw new IllegalArgumentException("n 必须为正数: " + n);
        }
        SpecState state = requireSpec(specName);
        int metricIndex = state.metricIndex(metricName);
        List<Map.Entry<GroupKey, GroupState>> entries = new ArrayList<>(state.groups.entrySet());
        SpecState current = state;
        entries.sort(Comparator
                .<Map.Entry<GroupKey, GroupState>>comparingDouble(
                        e -> rankValue(current, e.getValue(), metricIndex))
                .reversed()
                .thenComparing(e -> e.getKey().values().toString()));
        List<GroupResult> result = new ArrayList<>(Math.min(n, entries.size()));
        for (int i = 0; i < n && i < entries.size(); i++) {
            Map.Entry<GroupKey, GroupState> entry = entries.get(i);
            result.add(GroupResult.of(state.spec, entry.getKey(), state.snapshot(entry.getValue())));
        }
        return result;
    }

    private double rankValue(SpecState state, GroupState group, int metricIndex) {
        double value = group.metricValue(state.spec.getMetrics().get(metricIndex), metricIndex);
        return Double.isNaN(value) ? Double.NEGATIVE_INFINITY : value;
    }

    /** 某方案当前的分组数。 */
    public int groupCount(String specName) {
        return requireSpec(specName).groups.size();
    }

    /** 某方案因基数限制被合并的分组取值数（按维度累计）。 */
    public long mergedGroupCount(String specName) {
        return requireSpec(specName).mergedGroups();
    }

    /** 当前统计快照：分组数、指标计算次数、增量更新次数。 */
    public AggregationStats stats() {
        Map<String, Integer> groupCounts = new LinkedHashMap<>();
        Map<String, Long> merged = new LinkedHashMap<>();
        for (Map.Entry<String, SpecState> entry : specs.entrySet()) {
            groupCounts.put(entry.getKey(), entry.getValue().groups.size());
            merged.put(entry.getKey(), entry.getValue().mergedGroups());
        }
        return new AggregationStats(groupCounts, merged, metricEvaluations, incrementalUpdates);
    }

    private SpecState requireSpec(String specName) {
        SpecState state = specs.get(specName);
        if (state == null) {
            throw new IllegalArgumentException("未注册的分组方案: " + specName);
        }
        return state;
    }

    /** 单个分组方案的全部运行状态。 */
    private final class SpecState {
        private final GroupBySpec spec;
        private final Map<GroupKey, GroupState> groups = new HashMap<>();
        /** 维度 -> 已保留的不同取值（达到上限后不再增长）。 */
        private final Map<String, Set<String>> keptValues = new HashMap<>();
        /** 维度 -> 已被合并到 OTHER 桶的取值（用于去重统计与查询映射）。 */
        private final Map<String, Set<String>> overflowValues = new HashMap<>();

        SpecState(GroupBySpec spec) {
            this.spec = spec;
            for (String dimension : spec.getDimensions()) {
                keptValues.put(dimension, new HashSet<>());
                overflowValues.put(dimension, new HashSet<>());
            }
        }

        int metricCount() {
            return spec.getMetrics().size();
        }

        int metricIndex(String metricName) {
            List<MetricSpec> metrics = spec.getMetrics();
            for (int i = 0; i < metrics.size(); i++) {
                if (metrics.get(i).getName().equals(metricName)) {
                    return i;
                }
            }
            throw new IllegalArgumentException(
                    "方案 " + spec.getName() + " 中不存在指标: " + metricName);
        }

        GroupKey toGroupKey(Map<String, ?> event) {
            List<String> values = new ArrayList<>(spec.getDimensions().size());
            for (String dimension : spec.getDimensions()) {
                String raw = Objects.toString(event.get(dimension), "(null)");
                values.add(applyCardinality(dimension, raw));
            }
            return new GroupKey(values);
        }

        GroupKey toGroupKeyFromQuery(Map<String, String> key) {
            if (!key.keySet().equals(Set.copyOf(spec.getDimensions()))) {
                throw new IllegalArgumentException(
                        "查询键维度与方案 " + spec.getName() + " 不一致: 期望 "
                                + spec.getDimensions() + ", 实际 " + key.keySet());
            }
            List<String> values = new ArrayList<>(spec.getDimensions().size());
            for (String dimension : spec.getDimensions()) {
                values.add(mapForQuery(dimension, key.get(dimension)));
            }
            return new GroupKey(values);
        }

        /** 写入路径：新取值触发基数检查时登记保留/合并。 */
        private String applyCardinality(String dimension, String raw) {
            Integer limit = spec.getCardinalityLimits().get(dimension);
            if (limit == null) {
                return raw;
            }
            Set<String> kept = keptValues.get(dimension);
            if (kept.contains(raw)) {
                return raw;
            }
            if (kept.size() < limit) {
                kept.add(raw);
                return raw;
            }
            overflowValues.get(dimension).add(raw);
            return OTHER_BUCKET;
        }

        /** 查询路径：只读映射，不改变基数状态。 */
        private String mapForQuery(String dimension, String raw) {
            Integer limit = spec.getCardinalityLimits().get(dimension);
            if (limit == null) {
                return raw;
            }
            if (keptValues.get(dimension).contains(raw)) {
                return raw;
            }
            if (overflowValues.get(dimension).contains(raw)) {
                return OTHER_BUCKET;
            }
            return raw;
        }

        /** 只更新这一个分组的累加器（增量更新，不触碰其它分组）。 */
        void accumulate(GroupState group, Map<String, ?> event) {
            int evaluations = 0;
            List<MetricSpec> metrics = spec.getMetrics();
            group.eventCount++;
            for (int i = 0; i < metrics.size(); i++) {
                MetricSpec metric = metrics.get(i);
                if (metric.getType() == MetricType.COUNT) {
                    evaluations++;
                    continue;
                }
                Object rawValue = event.get(metric.getField());
                if (!(rawValue instanceof Number number)) {
                    continue;
                }
                double value = number.doubleValue();
                switch (metric.getType()) {
                    case SUM -> group.sums[i] += value;
                    case AVG -> {
                        group.sums[i] += value;
                        group.valueCounts[i]++;
                    }
                    case MAX -> group.maxes[i] = Math.max(group.maxes[i], value);
                    default -> throw new IllegalStateException("未知指标类型: " + metric.getType());
                }
                evaluations++;
            }
            metricEvaluations += evaluations;
        }

        MetricValues snapshot(GroupState group) {
            Map<String, Double> values = new LinkedHashMap<>();
            List<MetricSpec> metrics = spec.getMetrics();
            for (int i = 0; i < metrics.size(); i++) {
                values.put(metrics.get(i).getName(), group.metricValue(metrics.get(i), i));
            }
            return new MetricValues(values);
        }

        long mergedGroups() {
            return overflowValues.values().stream().mapToLong(Set::size).sum();
        }
    }

    /** 单个分组的累加器：COUNT 用事件数，SUM/AVG 用和与计数，MAX 用最大值。 */
    private static final class GroupState {
        private long eventCount;
        private final double[] sums;
        private final long[] valueCounts;
        private final double[] maxes;

        GroupState(int metricCount) {
            this.sums = new double[metricCount];
            this.valueCounts = new long[metricCount];
            this.maxes = new double[metricCount];
            java.util.Arrays.fill(this.maxes, Double.NEGATIVE_INFINITY);
        }

        double metricValue(MetricSpec metric, int index) {
            return switch (metric.getType()) {
                case COUNT -> (double) eventCount;
                case SUM -> sums[index];
                case AVG -> valueCounts[index] == 0 ? Double.NaN : sums[index] / valueCounts[index];
                case MAX -> maxes[index] == Double.NEGATIVE_INFINITY ? Double.NaN : maxes[index];
            };
        }
    }
}
