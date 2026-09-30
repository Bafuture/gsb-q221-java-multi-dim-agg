package com.example.gsb.agg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 覆盖：任意维度子集分组、多指标同时计算、结果查询、运行统计。
 */
class MultiDimAggregationTest {

    private MultiDimAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = MultiDimAggregator.builder()
                .view("省")
                .view("省", "城市")
                .view("城市", "渠道")
                .metric(MetricType.COUNT, MetricType.SUM, MetricType.AVG, MetricType.MAX)
                .build();
        aggregator.addEvents(List.of(
                Event.of(Map.of("省", "浙江", "城市", "杭州", "渠道", "app"), 100),
                Event.of(Map.of("省", "浙江", "城市", "杭州", "渠道", "app"), 10),
                Event.of(Map.of("省", "浙江", "城市", "宁波", "渠道", "web"), 50),
                Event.of(Map.of("省", "江苏", "城市", "苏州", "渠道", "app"), 20),
                Event.of(Map.of("省", "江苏", "城市", "南京", "渠道", "web"), 10)));
    }

    @Test
    void groupsBySingleDimension() {
        GroupResult zhejiang = aggregator.query(List.of("省"), "浙江");
        assertThat(zhejiang.found()).isTrue();
        assertThat(zhejiang.metrics().count()).isEqualTo(3);
        assertThat(zhejiang.metrics().sum()).isEqualTo(160.0);
        assertThat(zhejiang.metrics().avg()).isCloseTo(160.0 / 3, within(1e-9));
        assertThat(zhejiang.metrics().max()).isEqualTo(100.0);

        GroupResult jiangsu = aggregator.query(List.of("省"), "江苏");
        assertThat(jiangsu.metrics().count()).isEqualTo(2);
        assertThat(jiangsu.metrics().sum()).isEqualTo(30.0);
        assertThat(jiangsu.metrics().max()).isEqualTo(20.0);
    }

    @Test
    void groupsByMultipleDimensions() {
        GroupResult hangzhou = aggregator.query(List.of("省", "城市"), "浙江", "杭州");
        assertThat(hangzhou.found()).isTrue();
        assertThat(hangzhou.metrics().count()).isEqualTo(2);
        assertThat(hangzhou.metrics().sum()).isEqualTo(110.0);
        assertThat(hangzhou.metrics().avg()).isEqualTo(55.0);
        assertThat(hangzhou.metrics().max()).isEqualTo(100.0);

        GroupResult ningbo = aggregator.query(List.of("省", "城市"), "浙江", "宁波");
        assertThat(ningbo.metrics().count()).isEqualTo(1);
        assertThat(ningbo.metrics().sum()).isEqualTo(50.0);
    }

    @Test
    void groupsByArbitraryDimensionSubset() {
        GroupResult hangzhouApp = aggregator.query(List.of("城市", "渠道"), "杭州", "app");
        assertThat(hangzhouApp.found()).isTrue();
        assertThat(hangzhouApp.metrics().count()).isEqualTo(2);
        assertThat(hangzhouApp.metrics().sum()).isEqualTo(110.0);

        GroupResult suzhouApp = aggregator.query(List.of("城市", "渠道"), "苏州", "app");
        assertThat(suzhouApp.metrics().count()).isEqualTo(1);
        assertThat(suzhouApp.metrics().sum()).isEqualTo(20.0);

        GroupResult nanjingWeb = aggregator.query(List.of("城市", "渠道"), "南京", "web");
        assertThat(nanjingWeb.metrics().count()).isEqualTo(1);
    }

    @Test
    void queryByDimensionMap() {
        GroupResult result = aggregator.query(Map.of("省", "浙江", "城市", "杭州"), List.of("省", "城市"));
        assertThat(result.found()).isTrue();
        assertThat(result.metrics().sum()).isEqualTo(110.0);
    }

    @Test
    void missingGroupReturnsExplicitNotFound() {
        GroupResult missing = aggregator.query(List.of("省"), "广东");
        assertThat(missing.found()).isFalse();
        assertThat(missing.metrics()).isNull();
        assertThat(missing.key()).isEqualTo(AggregationKey.of("广东"));
    }

    @Test
    void nullDimensionValueIsNormalized() {
        aggregator.addEvent(Event.of(Map.of("省", "浙江", "渠道", "app"), 5));

        GroupResult result = aggregator.query(List.of("省", "城市"), "浙江", null);
        assertThat(result.found()).isTrue();
        assertThat(result.metrics().count()).isEqualTo(1);
        assertThat(result.metrics().sum()).isEqualTo(5.0);
        assertThat(result.key()).isEqualTo(AggregationKey.of("浙江", MultiDimAggregator.NULL_VALUE));
    }

    @Test
    void listsAllGroupsOfAViewSortedByKey() {
        List<GroupResult> groups = aggregator.groups(List.of("省", "城市"));
        assertThat(groups).hasSize(4);
        assertThat(groups).allMatch(GroupResult::found);
        assertThat(groups.stream().map(g -> g.key()))
                .containsExactly(
                        AggregationKey.of("江苏", "南京"),
                        AggregationKey.of("江苏", "苏州"),
                        AggregationKey.of("浙江", "宁波"),
                        AggregationKey.of("浙江", "杭州"));
    }

    @Test
    void reportsGroupCountMetricComputationsAndIncrementalUpdates() {
        AggregatorStats stats = aggregator.stats();
        assertThat(stats.groupCount())
                .containsEntry("省", 2)
                .containsEntry("省,城市", 4)
                .containsEntry("城市,渠道", 4);
        // 5 条数据 × 3 个视图 = 15 个受影响分组，每个分组计算 4 个指标
        assertThat(stats.incrementalUpdates()).isEqualTo(5);
        assertThat(stats.metricComputations()).isEqualTo(15 * 4);
    }
}
