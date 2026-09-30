package com.example.gsb.agg;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 覆盖：增量更新只改动受影响分组、不重算全部；统计计数器随之单调递增。
 */
class IncrementalUpdateTest {

    private MultiDimAggregator newAggregator() {
        return MultiDimAggregator.builder()
                .view("省")
                .view("省", "城市")
                .view("城市", "渠道")
                .metric(MetricType.COUNT, MetricType.SUM, MetricType.AVG, MetricType.MAX)
                .build();
    }

    @Test
    void onlyAffectedGroupsChange() {
        MultiDimAggregator aggregator = newAggregator();
        aggregator.addEvents(List.of(
                Event.of(Map.of("省", "浙江", "城市", "杭州", "渠道", "app"), 100),
                Event.of(Map.of("省", "浙江", "城市", "宁波", "渠道", "web"), 50),
                Event.of(Map.of("省", "江苏", "城市", "苏州", "渠道", "app"), 20),
                Event.of(Map.of("省", "江苏", "城市", "南京", "渠道", "web"), 10)));

        MetricValues jiangsuBefore = aggregator.query(List.of("省"), "江苏").metrics();
        MetricValues ningboBefore = aggregator.query(List.of("省", "城市"), "浙江", "宁波").metrics();
        MetricValues suzhouAppBefore = aggregator.query(List.of("城市", "渠道"), "苏州", "app").metrics();

        AggregatorStats statsBefore = aggregator.stats();
        assertThat(statsBefore.incrementalUpdates()).isEqualTo(4);
        assertThat(statsBefore.metricComputations()).isEqualTo(4L * 3 * 4);

        // 新数据只落入 杭州 / app，江苏、宁波、苏州+app 分组完全不应变化
        int touched = aggregator.addEvent(
                Event.of(Map.of("省", "浙江", "城市", "杭州", "渠道", "app"), 40));
        assertThat(touched).isEqualTo(3);

        MetricValues jiangsuAfter = aggregator.query(List.of("省"), "江苏").metrics();
        MetricValues ningboAfter = aggregator.query(List.of("省", "城市"), "浙江", "宁波").metrics();
        MetricValues suzhouAppAfter = aggregator.query(List.of("城市", "渠道"), "苏州", "app").metrics();
        assertThat(jiangsuAfter.count()).isEqualTo(jiangsuBefore.count());
        assertThat(jiangsuAfter.sum()).isEqualTo(jiangsuBefore.sum());
        assertThat(ningboAfter.count()).isEqualTo(ningboBefore.count());
        assertThat(ningboAfter.sum()).isEqualTo(ningboBefore.sum());
        assertThat(suzhouAppAfter.count()).isEqualTo(suzhouAppBefore.count());
        assertThat(suzhouAppAfter.sum()).isEqualTo(suzhouAppBefore.sum());

        // 受影响分组就地累加
        GroupResult hangzhou = aggregator.query(List.of("省", "城市"), "浙江", "杭州");
        assertThat(hangzhou.metrics().count()).isEqualTo(2);
        assertThat(hangzhou.metrics().sum()).isEqualTo(140.0);
        assertThat(hangzhou.metrics().avg()).isEqualTo(70.0);
        assertThat(hangzhou.metrics().max()).isEqualTo(100.0);

        AggregatorStats statsAfter = aggregator.stats();
        assertThat(statsAfter.incrementalUpdates()).isEqualTo(5);
        assertThat(statsAfter.metricComputations()).isEqualTo(statsBefore.metricComputations() + 3L * 4);
        assertThat(statsAfter.groupCount())
                .containsEntry("省", 2)
                .containsEntry("省,城市", 4)
                .containsEntry("城市,渠道", 4);
    }

    @Test
    void newGroupIsCreatedOnIncrementWithoutRecomputingOthers() {
        MultiDimAggregator aggregator = newAggregator();
        aggregator.addEvents(List.of(
                Event.of(Map.of("省", "浙江", "城市", "杭州", "渠道", "app"), 10),
                Event.of(Map.of("省", "浙江", "城市", "宁波", "渠道", "web"), 20)));

        assertThat(aggregator.query(List.of("省"), "广东").found()).isFalse();

        aggregator.addEvent(Event.of(Map.of("省", "广东", "城市", "广州", "渠道", "app"), 90));

        GroupResult guangdong = aggregator.query(List.of("省"), "广东");
        assertThat(guangdong.found()).isTrue();
        assertThat(guangdong.metrics().count()).isEqualTo(1);
        assertThat(guangdong.metrics().sum()).isEqualTo(90.0);

        // 老分组保持原值
        assertThat(aggregator.query(List.of("省"), "浙江").metrics().sum()).isEqualTo(30.0);
        assertThat(aggregator.stats().groupCount())
                .containsEntry("省", 2)
                .containsEntry("省,城市", 3)
                .containsEntry("城市,渠道", 3);
    }

    @Test
    void emptyAggregatorHasZeroStats() {
        AggregatorStats stats = newAggregator().stats();
        assertThat(stats.incrementalUpdates()).isZero();
        assertThat(stats.metricComputations()).isZero();
        assertThat(stats.groupCount().values()).allSatisfy(count -> assertThat(count).isZero());
    }
}
