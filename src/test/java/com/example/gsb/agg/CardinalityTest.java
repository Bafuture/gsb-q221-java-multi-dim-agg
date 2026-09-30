package com.example.gsb.agg;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 覆盖：维度基数上限把超出取值折叠进合并桶、分组数量受控、被合并组数统计。
 */
class CardinalityTest {

    @Test
    void foldsExcessValuesIntoOtherBucketAcrossAllViews() {
        MultiDimAggregator aggregator = MultiDimAggregator.builder()
                .view("渠道")
                .view("省", "渠道")
                .cardinalityLimit("渠道", 2)
                .metric(MetricType.COUNT, MetricType.SUM)
                .build();

        aggregator.addEvents(List.of(
                Event.of(Map.of("省", "浙江", "渠道", "app"), 10),
                Event.of(Map.of("省", "浙江", "渠道", "web"), 20),
                Event.of(Map.of("省", "浙江", "渠道", "app"), 30),
                Event.of(Map.of("省", "浙江", "渠道", "sms"), 40),
                Event.of(Map.of("省", "浙江", "渠道", "push"), 50),
                Event.of(Map.of("省", "浙江", "渠道", "app"), 60)));

        // app、web 为前 2 个保留取值，sms / push 折叠进合并桶
        assertThat(aggregator.query(List.of("渠道"), "app").metrics().count()).isEqualTo(3);
        assertThat(aggregator.query(List.of("渠道"), "app").metrics().sum()).isEqualTo(100.0);
        assertThat(aggregator.query(List.of("渠道"), "web").metrics().count()).isEqualTo(1);

        GroupResult other = aggregator.query(List.of("渠道"), MultiDimAggregator.OTHER_VALUE);
        assertThat(other.found()).isTrue();
        assertThat(other.metrics().count()).isEqualTo(2);
        assertThat(other.metrics().sum()).isEqualTo(90.0);

        GroupResult provinceOther = aggregator.query(
                List.of("省", "渠道"), "浙江", MultiDimAggregator.OTHER_VALUE);
        assertThat(provinceOther.found()).isTrue();
        assertThat(provinceOther.metrics().count()).isEqualTo(2);
        assertThat(provinceOther.metrics().sum()).isEqualTo(90.0);

        CardinalityStats stats = aggregator.cardinalityOf("渠道");
        assertThat(stats.limit()).isEqualTo(2);
        assertThat(stats.distinctValues()).isEqualTo(4);
        assertThat(stats.retainedValues()).isEqualTo(2);
        assertThat(stats.mergedValues()).isEqualTo(2);
        assertThat(stats.mergedGroups()).isEqualTo(2);
        assertThat(stats.capped()).isTrue();

        AggregatorStats global = aggregator.stats();
        assertThat(global.groupCount())
                .containsEntry("渠道", 3)
                .containsEntry("省,渠道", 3);
    }

    @Test
    void mergesGroupsWithinCappedDimension() {
        MultiDimAggregator aggregator = MultiDimAggregator.builder()
                .view("省")
                .cardinalityLimit("省", 1)
                .metric(MetricType.COUNT, MetricType.SUM)
                .build();

        aggregator.addEvents(List.of(
                Event.of(Map.of("省", "浙江"), 10),
                Event.of(Map.of("省", "江苏"), 20),
                Event.of(Map.of("省", "广东"), 30)));

        assertThat(aggregator.groups(List.of("省"))).hasSize(2);
        GroupResult other = aggregator.query(List.of("省"), MultiDimAggregator.OTHER_VALUE);
        assertThat(other.metrics().count()).isEqualTo(2);
        assertThat(other.metrics().sum()).isEqualTo(50.0);

        CardinalityStats stats = aggregator.cardinalityOf("省");
        assertThat(stats.distinctValues()).isEqualTo(3);
        assertThat(stats.retainedValues()).isEqualTo(1);
        assertThat(stats.mergedValues()).isEqualTo(2);
        assertThat(stats.mergedGroups()).isEqualTo(2);
    }

    @Test
    void uncappedDimensionReportsNoMerging() {
        MultiDimAggregator aggregator = MultiDimAggregator.builder()
                .view("渠道")
                .metric(MetricType.COUNT)
                .build();

        aggregator.addEvent(Event.of(Map.of("渠道", "app"), 1));

        CardinalityStats stats = aggregator.cardinalityOf("渠道");
        assertThat(stats.limit()).isEqualTo(Integer.MAX_VALUE);
        assertThat(stats.capped()).isFalse();
        assertThat(stats.mergedValues()).isZero();
    }
}
