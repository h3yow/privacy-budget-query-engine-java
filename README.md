# 差分隐私聚合查询引擎（本地模拟）

用本地确定性数据模拟多主体提交的统计请求，演示隐私预算的消耗、结果扰动与失败回滚，
使各入口、并发与异常路径下的行为**可解释且可复现**。

## 隐私参数语义

| 参数 | 语义 | 合法范围 |
| --- | --- | --- |
| `epsilon` | 单次查询消耗的隐私预算，同时决定噪声尺度 `b = 灵敏度 / epsilon`（epsilon 越小噪声越大） | `(0, 10]` |
| `delta` | 近似差分隐私的松弛项（当前实现为纯 ε-DP 的拉普拉斯机制，delta 仅作声明与校验） | `[0, 1e-3]` |
| `minGroupSize` | 最小匿名度：分组记录数低于该值时直接拒绝 | `>= 0` |
| `seed` | 扰动种子：相同种子 + 相同输入（domain/groupKey）产生完全一致的扰动结果 | 任意 long |

灵敏度：单条记录取值被裁剪到 `[0, 100]`，故 sum 查询的 L1 灵敏度为 `100`。

## 预算与回滚规则

每个数据域（`health` / `finance`）拥有独立账本（默认总额 `epsilon = 5.0`），
查询按以下固定顺序执行：

1. **参数校验**：epsilon/delta 越界 → `REJECTED_INVALID_PARAMETER`，不触碰预算；
2. **匿名度前置校验**：分组规模不足 → `REJECTED_INSUFFICIENT_ANONYMITY`，不消耗预算；
3. **预算原子扣减**：`tryConsume` 在监视器上完成"检查 + 扣减"，并发下不会超支；
   余额不足 → `REJECTED_BUDGET_EXHAUSTED`，账本状态不变；
4. **扰动输出**：扣减后若扰动阶段抛异常，立即 `refund` 回滚，返回拒绝结果。

守恒不变式：`remaining == total - consumed` 恒成立；任何拒绝路径
`value == null` 且 `epsilonConsumed == 0`，**绝不返回未扰动的原始值**。

## 接口

```
POST /api/query
{"domain":"health","groupKey":"age-30-40","epsilon":1.0,"delta":1e-5,"minGroupSize":5,"seed":42}
```

响应：`{"status":"OK","value":...,"epsilonConsumed":1.0,"budgetRemaining":4.0,"reason":"OK"}`
或 `{"status":"REJECTED_*","value":null,"epsilonConsumed":0.0,"budgetRemaining":...,"reason":"..."}`。

本地模拟数据（固定种子生成，含 3 条记录的小分组 `health/rare-cohort` 用于触发匿名度拒绝）：

| domain | groupKey | 记录数 |
| --- | --- | --- |
| health | age-30-40 / age-40-50 / rare-cohort | 64 / 48 / 3 |
| finance | income-mid / income-high | 80 / 32 |

## 本地验证

```bash
mvn test          # 运行全部单元与集成测试
mvn spring-boot:run
curl -X POST localhost:8080/api/query -H 'Content-Type: application/json' \
     -d '{"domain":"health","groupKey":"age-30-40","epsilon":1.0,"delta":1e-5,"minGroupSize":5,"seed":42}'
```

测试覆盖（日志会打印请求参数、预算变动与判定依据）：

- `AggregationQueryServiceTest`
  - 相同种子/输入结果一致，不同种子产生差异；
  - 参数越界（epsilon/delta/未知域）拒绝且预算守恒；
  - 匿名度不足拒绝且不消耗预算；
  - 预算耗尽拒绝且原因可区分；
  - **32 线程并发争抢 5.0 预算**：恰好 5 次成功，消耗合计 + 剩余 == 总预算（无重复扣减、无漏扣）；
  - 混合成功/失败路径后的预算守恒。
- `QueryControllerTest`：REST 入口下的成功、预算耗尽、参数越界与匿名度不足路径。
