package com.example.gsb.agg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 分组键：与某个视图的维度列表一一对应的有序取值序列。
 * 实现了 {@link Comparable}，作为 TopN 等指标相同情况下的稳定次序依据。
 */
public final class AggregationKey implements Comparable<AggregationKey> {

    private final List<String> values;

    public AggregationKey(List<String> values) {
        Objects.requireNonNull(values, "values must not be null");
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    public static AggregationKey of(String... values) {
        return new AggregationKey(List.of(values));
    }

    public List<String> values() {
        return values;
    }

    @Override
    public int compareTo(AggregationKey other) {
        int common = Math.min(values.size(), other.values.size());
        for (int i = 0; i < common; i++) {
            int cmp = values.get(i).compareTo(other.values.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(values.size(), other.values.size());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AggregationKey other)) {
            return false;
        }
        return values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "AggregationKey" + values;
    }
}
