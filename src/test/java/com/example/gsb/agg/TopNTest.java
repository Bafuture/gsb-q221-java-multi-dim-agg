package com.example.gsb.agg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 覆盖：全局 TopN 与“每个父分组内取前 N”的组合 TopN、不足 N 按实际返回、并列次序稳定。
 */
class TopNTest {

    private MultiDimAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = MultiDimAggregator.builder()
                .view("省")
                .view("省", "城市")
                .view("省", "城市", "渠道")
                .metric(MetricType.COUNT, MetricType.SUM)
                .build();
        aggregator.addEvents(List.of(
                Event.of(Map.of("省", "浙江", "城市", "杭州", "渠道", "app"), 100),
                Event.of(Map.of("省", "浙江", "城市", "杭州", "渠道", "app"), 10),
                Event.of(Map.of("省", "浙江", "城市", "宁波", "渠道", "web"), 50),
                Event.of(Map.of("省", "江苏", "城市", "苏州", "渠道", "app"), 20),
                Event.of(Map.of("省", "江苏", "城市", "南京", "渠道", "web"), 10)));
    }

    @Test
    void globalTopNBySum() {
        List<GroupResult> top = aggregator.topN(List.of("省", "城市"), MetricType.SUM, 2);
        assertThat(top).hasSize(2);
        assertThat(top.get(0).key()).isEqualTo(AggregationKey.of("浙江", "杭州"));
        assertThat(top.get(0).metrics().sum()).isEqualTo(110.0);
        assertThat(top.get(1).key()).isEqualTo(AggregationKey.of("浙江", "宁波"));
    }

    @Test
    void topNReturnsAllWhenFewerThanN() {
        List<GroupResult> top = aggregator.topN(List.of("省", "城市"), MetricType.SUM, 10);
        assertThat(top).hasSize(4);
    }

    @Test
    void topNByCountWithStableKeyTieBreaker() {
        List<GroupResult> top = aggregator.topN(List.of("省", "城市"), MetricType.COUNT, 4);
        assertThat(top).hasSize(4);
        // 杭州 count=2 居首；其余 count=1 按分组键升序
        assertThat(top.get(0).key()).isEqualTo(AggregationKey.of("浙江", "杭州"));
        assertThat(top.subList(1, 4).stream().map(GroupResult::key))
                .containsExactly(
                        AggregationKey.of("江苏", "南京"),
                        AggregationKey.of("江苏", "苏州"),
                        AggregationKey.of("浙江", "宁波"));
    }

    @Test
    void combinedTopNWithinEachParent() {
        Map<AggregationKey, List<GroupResult>> byProvince =
                aggregator.topNWithin(List.of("省"), List.of("省", "城市"), MetricType.SUM, 1);

        assertThat(byProvince).containsOnlyKeys(AggregationKey.of("江苏"), AggregationKey.of("浙江"));
        assertThat(byProvince.get(AggregationKey.of("浙江")))
                .singleElement()
                .extracting(GroupResult::key)
                .isEqualTo(AggregationKey.of("浙江", "杭州"));
        assertThat(byProvince.get(AggregationKey.of("江苏")))
                .singleElement()
                .extracting(GroupResult::key)
                .isEqualTo(AggregationKey.of("江苏", "苏州"));
    }

    @Test
    void combinedTopNAcrossThreeLevels() {
        Map<AggregationKey, List<GroupResult>> byCity = aggregator.topNWithin(
                List.of("省", "城市"), List.of("省", "城市", "渠道"), MetricType.SUM, 1);

        assertThat(byCity).hasSize(4);
        assertThat(byCity.get(AggregationKey.of("浙江", "杭州")))
                .singleElement()
                .extracting(GroupResult::key)
                .isEqualTo(AggregationKey.of("浙江", "杭州", "app"));
        assertThat(byCity.get(AggregationKey.of("江苏", "南京")))
                .singleElement()
                .extracting(GroupResult::key)
                .isEqualTo(AggregationKey.of("江苏", "南京", "web"));
    }

    @Test
    void combinedTopNReturnsActualSizeWhenChildrenFewerThanN() {
        Map<AggregationKey, List<GroupResult>> byProvince =
                aggregator.topNWithin(List.of("省"), List.of("省", "城市"), MetricType.SUM, 5);
        assertThat(byProvince.get(AggregationKey.of("江苏"))).hasSize(2);
        assertThat(byProvince.get(AggregationKey.of("浙江"))).hasSize(2);
    }

    @Test
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> aggregator.topN(List.of("省"), MetricType.SUM, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> aggregator.topN(List.of("不存在"), MetricType.SUM, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> aggregator.topNWithin(
                List.of("省", "城市", "渠道"), List.of("省", "城市"), MetricType.SUM, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("extend parent");
    }
}
