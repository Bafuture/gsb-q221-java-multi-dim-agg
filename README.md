# 多维聚合与组合 TopN

Pair-wise GSB 标注任务仓库（第 15 批 / 221）。

| 项目 | 内容 |
|------|------|
| 任务类型 | Feature 迭代 |
| 任务难度 | 困难 |
| 语言/框架 | Java, Maven, JUnit 5 |
| 环境可复现等级 | 无外部依赖 |
| 构建方式 | Maven（含 mvnw wrapper，无需本机安装 Maven） |

> 本仓库是**初始环境快照**：只有工程骨架，不含任何实现代码。
> 分支说明：`main` 为初始环境；`A`、`B` 为两次独立执行各自的工作分支，均从 `main` 的同一个提交拉出。

## 运行方式

```bash
./mvnw -q verify
```

## 任务提示词

以下为本题完整的 User Prompt 原文，两次执行必须使用完全相同的文本。

我们的指标要支持按多个维度自由组合统计，还要能取每个组合下的 TopN。请从零实现一个多维聚合组件。仓库目前只有一个空的 Maven 工程（pom.xml 只声明 JUnit 5 与 AssertJ）。要求：1) 支持按任意维度子集分组，例如按省、按省加城市、按城市加渠道；2) 支持多种聚合指标（计数、求和、平均值、最大值）同时计算；3) 支持组合 TopN：每个分组内按指定指标取前 N，不足 N 时按实际返回；4) 支持维度基数控制：某维度取值过多时要能限制分组数量并统计被合并的组数；5) 支持增量更新：新数据到来时只更新受影响的分组，不重算全部；6) 支持结果查询：按分组键查询指标值，不存在的分组要返回明确结果；7) 提供统计：分组数、指标计算次数与增量更新次数；8) 测试覆盖多维度组合、多指标、组合 TopN、基数限制、增量更新与查询；`mvn -q verify` 一条命令跑通。

## 提交要求

1. 在本仓库中完成提示词要求的全部内容。
2. `./mvnw -q verify` 必须通过。
3. 完成后在所属分支（A 或 B）上提交，产物快照的父提交必须是初始环境快照。

## 实现说明：多维聚合组件

核心类位于 `src/main/java/com/example/gsb/agg/`，入口为 `MultiDimAggregator`。

- `GroupBySpec`：一个分组方案，声明维度子集（如 `province`、`province+city`、`city+channel`）、
  指标列表（`count/sum/avg/max`，可并行计算）以及每维度的基数上限。
- `MultiDimAggregator#register`：可注册多个方案，一条数据会更新所有方案；
  `addEvent` 只定位并更新各方案中受影响的那一个分组（增量更新，不重算全部）。
- `topN(spec, metric, n)`：按指定指标对分组降序取前 N，不足 N 按实际返回，
  指标相同按分组键字典序稳定排序。
- 维度取值超过基数上限时，新取值合并到 `__OTHER__` 桶，被合并的不同取值数由
  `mergedGroupCount` / `AggregationStats` 统计；查询时被合并的取值会自动映射到该桶。
- `query(spec, key)`：按分组键查询，命中返回 `Optional<GroupResult>`，未命中返回
  `Optional.empty()`，键维度与方案不匹配抛异常。
- `stats()`：返回各方案分组数、被合并组数、指标计算次数与增量更新次数。

示例：

```java
MultiDimAggregator agg = new MultiDimAggregator();
agg.register(GroupBySpec.builder("city_sales")
        .dimensions("province", "city")
        .metrics(MetricSpec.count(), MetricSpec.sum("amount"),
                 MetricSpec.avg("amount"), MetricSpec.max("amount"))
        .cardinalityLimit("city", 100)
        .build());
agg.addEvent(Map.of("province", "江苏", "city", "南京", "amount", 42));
GroupResult r = agg.query("city_sales", Map.of("province", "江苏", "city", "南京")).orElseThrow();
List<GroupResult> top = agg.topN("city_sales", "sum_amount", 10);
AggregationStats stats = agg.stats();
```
