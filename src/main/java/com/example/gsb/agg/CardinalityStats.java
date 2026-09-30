package com.example.gsb.agg;

/**
 * 单个维度的基数控制统计（不可变快照）。
 *
 * @param limit          该维度允许保留的不同取值个数上限；{@link Integer#MAX_VALUE} 表示不限制
 * @param distinctValues 原始数据中出现过的不同取值总数（含 null）
 * @param retainedValues 保留为独立分组键的取值个数（不含合并桶）
 * @param mergedValues   被折叠进合并桶的不同原始取值个数 = distinctValues - retainedValues
 * @param mergedGroups   因合并而减少的“潜在分组数”：单维视角下与 mergedValues 相等
 */
public record CardinalityStats(int limit,
                               int distinctValues,
                               int retainedValues,
                               int mergedValues,
                               int mergedGroups) {

    public boolean capped() {
        return mergedValues > 0;
    }
}
