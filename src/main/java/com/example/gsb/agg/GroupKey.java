package com.example.gsb.agg;

import java.util.List;
import java.util.Objects;

/**
 * 不可变分组键：各维度取值（已做基数合并映射），按方案的维度顺序排列。
 */
public record GroupKey(List<String> values) {

    public GroupKey {
        values = List.copyOf(values);
    }

    static GroupKey of(String... values) {
        return new GroupKey(List.of(values));
    }

    public String get(int index) {
        return values.get(index);
    }

    public int size() {
        return values.size();
    }

    @Override
    public String toString() {
        return values.toString();
    }

    /** 与 {@link List#equals} 兼容，避免 record 隐式构造器的泛型比较问题。 */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GroupKey other)) {
            return false;
        }
        return Objects.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(values);
    }
}
