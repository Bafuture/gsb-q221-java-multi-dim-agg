package com.example.gsb.agg;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一条待聚合的数据：维度取值集合 + 一个数值度量。
 * 维度取值允许为 {@code null}，会被规范化为 {@link MultiDimAggregator#NULL_VALUE}。
 */
public final class Event {

    private final Map<String, String> dimensions;
    private final double measure;

    public Event(Map<String, String> dimensions, double measure) {
        Objects.requireNonNull(dimensions, "dimensions must not be null");
        this.dimensions = Collections.unmodifiableMap(new LinkedHashMap<>(dimensions));
        this.measure = measure;
    }

    public static Event of(Map<String, String> dimensions, double measure) {
        return new Event(dimensions, measure);
    }

    public Map<String, String> dimensions() {
        return dimensions;
    }

    public double measure() {
        return measure;
    }
}
