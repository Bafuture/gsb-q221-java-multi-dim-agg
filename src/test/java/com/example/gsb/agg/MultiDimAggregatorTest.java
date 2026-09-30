package com.example.gsb.agg;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultiDimAggregatorTest {

    private MultiDimAggregator aggregator;

    private static Map<String, Object> event(String province, String city, String channel, double amount) {
        return Map.of("province", province, "city", city, "channel", channel, "amount", amount);
    }

    private static Map<String, Object> event(String province, String city, String channel) {
        return Map.of("province", province, "city", city, "channel", channel);
    }

    @BeforeEach
    void setUp() {
        aggregator = new MultiDimAggregator();
    }

    @Nested
    @DisplayName("多维度自由组合分组")
    class DimensionCombination {

        @Test
        @DisplayName("按省 / 按省+城市 / 按城市+渠道三个方案同时聚合")
        void groupsByAnyDimensionSubset() {
            aggregator.register(GroupBySpec.builder("by_province")
                    .dimension("province").metric(MetricSpec.count()).build());
            aggregator.register(GroupBySpec.builder("by_province_city")
                    .dimensions("province", "city").metric(MetricSpec.count()).build());
            aggregator.register(GroupBySpec.builder("by_city_channel")
                    .dimensions("city", "channel").metric(MetricSpec.count()).build());

            aggregator.addAll(List.of(
                    event("江苏", "南京", "app", 10),
                    event("江苏", "南京", "web", 20),
                    event("江苏", "苏州", "app", 30),
                    event("浙江", "杭州", "app", 40)));

            assertThat(aggregator.groupCount("by_province")).isEqualTo(2);
            assertThat(aggregator.groupCount("by_province_city")).isEqualTo(3);
            assertThat(aggregator.groupCount("by_city_channel")).isEqualTo(4);

            assertThat(aggregator.query("by_province", Map.of("province", "江苏"))
                    .orElseThrow().metric("count")).isEqualTo(3);
            assertThat(aggregator.query("by_province_city", Map.of("province", "江苏", "city", "南京"))
                    .orElseThrow().metric("count")).isEqualTo(2);
            assertThat(aggregator.query("by_city_channel", Map.of("city", "杭州", "channel", "app"))
                    .orElseThrow().metric("count")).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("多种指标同时计算")
    class MultipleMetrics {

        @Test
        @DisplayName("count/sum/avg/max 在同一分组上一次更新同时得到")
        void allMetricTypesTogether() {
            aggregator.register(GroupBySpec.builder("sales")
                    .dimension("province")
                    .metrics(MetricSpec.count(),
                            MetricSpec.sum("amount"),
                            MetricSpec.avg("amount"),
                            MetricSpec.max("amount"))
                    .build());

            aggregator.addAll(List.of(
                    event("江苏", "南京", "app", 10),
                    event("江苏", "苏州", "app", 20),
                    event("江苏", "苏州", "web", 30)));

            GroupResult result = aggregator
                    .query("sales", Map.of("province", "江苏")).orElseThrow();
            assertThat(result.metric("count")).isEqualTo(3);
            assertThat(result.metric("sum_amount")).isEqualTo(60);
            assertThat(result.metric("avg_amount")).isEqualTo(20);
            assertThat(result.metric("max_amount")).isEqualTo(30);
        }

        @Test
        @DisplayName("缺失/非数值的度量字段不参与 sum/avg/max，但计数仍然生效")
        void skipsNonNumericMeasure() {
            aggregator.register(GroupBySpec.builder("sales")
                    .dimension("province")
                    .metrics(MetricSpec.count(),
                            MetricSpec.sum("amount"),
                            MetricSpec.avg("amount"),
                            MetricSpec.max("amount"))
                    .build());

            aggregator.addEvent(Map.of("province", "江苏", "city", "南京", "amount", 10));
            aggregator.addEvent(event("江苏", "苏州", "app"));
            aggregator.addEvent(Map.of("province", "江苏", "amount", "bad"));

            GroupResult result = aggregator.query("sales", Map.of("province", "江苏")).orElseThrow();
            assertThat(result.metric("count")).isEqualTo(3);
            assertThat(result.metric("sum_amount")).isEqualTo(10);
            assertThat(result.metric("avg_amount")).isEqualTo(10);
            assertThat(result.metric("max_amount")).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("组合 TopN")
    class TopN {

        @BeforeEach
        void registerSpec() {
            aggregator.register(GroupBySpec.builder("city_sales")
                    .dimension("city")
                    .metrics(MetricSpec.count(), MetricSpec.sum("amount"), MetricSpec.max("amount"))
                    .build());
            aggregator.addAll(List.of(
                    event("江苏", "南京", "app", 10),
                    event("江苏", "南京", "web", 40),
                    event("江苏", "苏州", "app", 100),
                    event("浙江", "杭州", "app", 70),
                    event("浙江", "宁波", "app", 25)));
        }

        @Test
        @DisplayName("按指定指标降序取前 N，且只返回 N 个")
        void topNBySpecifiedMetric() {
            List<GroupResult> top2 = aggregator.topN("city_sales", "sum_amount", 2);
            assertThat(top2).hasSize(2);
            assertThat(top2.get(0).dimensions()).containsEntry("city", "苏州");
            assertThat(top2.get(0).metric("sum_amount")).isEqualTo(100);
            assertThat(top2.get(1).dimensions()).containsEntry("city", "杭州");
            assertThat(top2.get(1).metric("sum_amount")).isEqualTo(70);
        }

        @Test
        @DisplayName("不足 N 时按实际数量返回，并列值按分组键字典序稳定排序")
        void fewerThanNReturnsAll() {
            List<GroupResult> top10 = aggregator.topN("city_sales", "sum_amount", 10);
            assertThat(top10).hasSize(4);
            assertThat(top10).extracting(r -> r.dimensions().get("city"))
                    .containsExactly("苏州", "杭州", "南京", "宁波");
        }

        @Test
        @DisplayName("可按不同指标取 TopN（如按计数）")
        void topNByCount() {
            List<GroupResult> top = aggregator.topN("city_sales", "count", 1);
            assertThat(top).singleElement()
                    .extracting(r -> r.dimensions().get("city")).isEqualTo("南京");
        }

        @Test
        @DisplayName("n 必须为正数")
        void rejectsInvalidN() {
            assertThatThrownBy(() -> aggregator.topN("city_sales", "sum_amount", 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("维度基数控制")
    class CardinalityLimit {

        @Test
        @DisplayName("超出上限的取值合并到 OTHER 桶，并统计被合并的组数")
        @SuppressWarnings("unchecked")
        void mergesOverflowValuesIntoOtherBucket() {
            aggregator.register(GroupBySpec.builder("by_city")
                    .dimension("city")
                    .metric(MetricSpec.count())
                    .metric(MetricSpec.sum("amount"))
                    .cardinalityLimit("city", 2)
                    .build());

            aggregator.addAll(List.of(
                    event("江苏", "南京", "app", 10),
                    event("江苏", "苏州", "app", 20),
                    event("江苏", "无锡", "app", 30),
                    event("江苏", "常州", "app", 40),
                    event("江苏", "无锡", "web", 50)));

            assertThat(aggregator.groupCount("by_city")).isEqualTo(3);
            assertThat(aggregator.mergedGroupCount("by_city")).isEqualTo(2);

            GroupResult other = aggregator
                    .query("by_city", Map.of("city", MultiDimAggregator.OTHER_BUCKET))
                    .orElseThrow();
            assertThat(other.metric("count")).isEqualTo(3);
            assertThat(other.metric("sum_amount")).isEqualTo(120);

            // 被合并的具体取值查询时自动映射到 OTHER 桶
            assertThat(aggregator.query("by_city", Map.of("city", "无锡")))
                    .get().extracting(r -> r.dimensions().get("city"))
                    .isEqualTo(MultiDimAggregator.OTHER_BUCKET);
        }

        @Test
        @DisplayName("同一溢出取值重复出现只计一次被合并组数")
        void mergedGroupCountIsByDistinctValue() {
            aggregator.register(GroupBySpec.builder("by_city")
                    .dimension("city").metric(MetricSpec.count())
                    .cardinalityLimit("city", 1).build());

            aggregator.addAll(List.of(
                    event("江苏", "南京", "app", 1),
                    event("江苏", "苏州", "app", 2),
                    event("江苏", "苏州", "web", 3),
                    event("江苏", "无锡", "app", 4)));

            assertThat(aggregator.groupCount("by_city")).isEqualTo(2);
            assertThat(aggregator.mergedGroupCount("by_city")).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("增量更新")
    class IncrementalUpdate {

        @Test
        @DisplayName("新数据只更新受影响分组，已有分组结果保持并累加")
        void onlyAffectedGroupChanges() {
            aggregator.register(GroupBySpec.builder("by_province")
                    .dimension("province")
                    .metrics(MetricSpec.count(), MetricSpec.sum("amount"))
                    .build());

            aggregator.addEvent(event("江苏", "南京", "app", 10));
            aggregator.addEvent(event("浙江", "杭州", "app", 100));

            GroupResult jiangsuBefore = aggregator.query("by_province", Map.of("province", "江苏")).orElseThrow();
            assertThat(jiangsuBefore.metric("count")).isEqualTo(1);
            assertThat(jiangsuBefore.metric("sum_amount")).isEqualTo(10);

            // 一条只属于江苏的新数据：江苏累加，浙江不变，不重算全部
            aggregator.addEvent(event("江苏", "苏州", "web", 5));

            assertThat(aggregator.query("by_province", Map.of("province", "江苏")).orElseThrow()
                    .metric("sum_amount")).isEqualTo(15);
            assertThat(aggregator.query("by_province", Map.of("province", "浙江")).orElseThrow()
                    .metric("sum_amount")).isEqualTo(100);
            assertThat(aggregator.groupCount("by_province")).isEqualTo(2);

            AggregationStats stats = aggregator.stats();
            assertThat(stats.incrementalUpdates()).isEqualTo(3);
            assertThat(stats.metricEvaluations()).isEqualTo(6);
        }
    }

    @Nested
    @DisplayName("结果查询与统计")
    class QueryAndStats {

        @Test
        @DisplayName("不存在的分组返回 Optional.empty，维度不匹配报错")
        void missingGroupReturnsEmpty() {
            aggregator.register(GroupBySpec.builder("by_province_city")
                    .dimensions("province", "city").metric(MetricSpec.count()).build());
            aggregator.addEvent(event("江苏", "南京", "app", 10));

            Optional<GroupResult> missing = aggregator
                    .query("by_province_city", Map.of("province", "浙江", "city", "杭州"));
            assertThat(missing).isEmpty();

            assertThatThrownBy(() ->
                    aggregator.query("by_province_city", Map.of("province", "江苏")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> aggregator.query("unknown", Map.of("x", "y")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("stats 汇总各方案分组数、合并组数、指标计算次数与更新次数")
        void statsSnapshot() {
            aggregator.register(GroupBySpec.builder("p")
                    .dimension("province")
                    .metrics(MetricSpec.count(), MetricSpec.sum("amount"))
                    .build());
            aggregator.register(GroupBySpec.builder("pc")
                    .dimensions("province", "city")
                    .metric(MetricSpec.count())
                    .cardinalityLimit("city", 1)
                    .build());

            aggregator.addAll(List.of(
                    event("江苏", "南京", "app", 10),
                    event("江苏", "苏州", "app", 20),
                    event("浙江", "杭州", "app", 30)));

            AggregationStats stats = aggregator.stats();
            assertThat(stats.groupCountBySpec()).containsEntry("p", 2).containsEntry("pc", 3);
            assertThat(stats.totalGroupCount()).isEqualTo(5);
            assertThat(stats.mergedGroupsBySpec()).containsEntry("p", 0L).containsEntry("pc", 2L);
            assertThat(stats.totalMergedGroups()).isEqualTo(2);
            // 3 条事件 * (p 方案 2 个指标 + pc 方案 1 个指标)
            assertThat(stats.metricEvaluations()).isEqualTo(9);
            assertThat(stats.incrementalUpdates()).isEqualTo(3);
        }
    }
}
