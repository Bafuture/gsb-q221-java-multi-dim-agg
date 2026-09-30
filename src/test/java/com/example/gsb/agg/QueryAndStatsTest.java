package com.example.gsb.agg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 覆盖：查询边界（不存在分组、键维度不匹配、未注册视图）、指标快照只读契约。
 */
class QueryAndStatsTest {

    private MultiDimAggregator newAggregator() {
        return MultiDimAggregator.builder()
                .view("省")
                .view("省", "城市")
                .metric(MetricType.COUNT)
                .build();
    }

    @Test
    void missingGroupIsExplicitResult() {
        GroupResult result = newAggregator().query(List.of("省"), "西藏");
        assertThat(result.found()).isFalse();
        assertThat(result.metrics()).isNull();
        assertThat(result.viewDimensions()).containsExactly("省");
        assertThat(result.key()).isEqualTo(AggregationKey.of("西藏"));
        assertThat(result.metricsOrElse(null)).isNull();
    }

    @Test
    void queryRejectsKeySizeMismatch() {
        MultiDimAggregator aggregator = newAggregator();
        assertThatThrownBy(() -> aggregator.query(List.of("省", "城市"), "浙江"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key has 1 values but view has 2 dimensions");
    }

    @Test
    void queryRejectsUnknownView() {
        assertThatThrownBy(() -> newAggregator().query(List.of("渠道"), "app"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("view not registered");
    }

    @Test
    void unconfiguredMetricCannotBeRead() {
        MultiDimAggregator aggregator = newAggregator();
        aggregator.addEvent(Event.of(Map.of("省", "浙江", "城市", "杭州"), 5));
        MetricValues metrics = aggregator.query(List.of("省"), "浙江").metrics();
        assertThat(metrics.contains(MetricType.COUNT)).isTrue();
        assertThat(metrics.contains(MetricType.SUM)).isFalse();
        assertThat(metrics.count()).isEqualTo(1);
        assertThatThrownBy(metrics::sum)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUM");
    }

    @Test
    void builderRequiresMetricAndView() {
        assertThatThrownBy(() -> MultiDimAggregator.builder().metric(MetricType.COUNT).build())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MultiDimAggregator.builder().view("省").build())
                .isInstanceOf(IllegalStateException.class);
    }
}
