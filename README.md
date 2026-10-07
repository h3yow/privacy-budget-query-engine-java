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
2. **会话前置校验**（仅带 `sessionId` 的请求）：会话不存在/已回收/跨域 →
   `REJECTED_SESSION_INVALID`，不消耗预算；
3. **匿名度前置校验**：分组规模不足 → `REJECTED_INSUFFICIENT_ANONYMITY`，不消耗预算；
4. **预算原子扣减**：老方式从数据域账本扣、会话请求从会话额度内扣，
   "检查 + 扣减"在监视器上原子完成，并发下不会超支；
   余额不足 → `REJECTED_BUDGET_EXHAUSTED`，账本状态不变；
   会话已过期/已结束 → `REJECTED_SESSION_INVALID`；
5. **扰动输出**：扣减后若扰动阶段抛异常，立即回滚（账本 `refund` 或会话内回滚），
   返回拒绝结果。

守恒不变式：`consumed + reserved + available == total` 恒成立
（即 `remaining == total - consumed - reserved`）；任何拒绝路径
`value == null` 且 `epsilonConsumed == 0`，**绝不返回未扰动的原始值**。

## 会话额度

调用方可为某个数据域一次性申请一段**可重复使用的会话额度**，之后的查询都带
`sessionId` 从这段额度里扣，直到用完或主动结束。

### 语义

- **申请即占用**：`POST /api/sessions` 成功时，申请量从数据域账本的"可用"划入
  "被占"（reserved），申请量不能超过该域当前实际可用预算，否则申请被拒绝；
- **会话内扣减**：带 `sessionId` 的查询从会话额度内扣（被占 → 实耗），
  同一会话并发使用不会重复花，累计消耗不超过申请量；
  会话内剩余额度不足时返回 `REJECTED_BUDGET_EXHAUSTED`；
- **失效即拒绝**：会话不存在、已过期、已结束或跨数据域使用时，
  返回 `REJECTED_SESSION_INVALID`（与参数越界、匿名度不足明确区分），
  不消耗任何预算，也绝不返回原始或扰动后的结果。

### 生命周期与回收规则

| 触发 | 行为 |
| --- | --- |
| 主动结束（`DELETE /api/sessions/{id}`） | 未花完的部分立即归还数据域可用余额；幂等，重复结束不会重复归还 |
| 空闲超时（默认 60s 无访问，申请时可传 `idleTimeoutMillis` 覆盖） | 查询路径上惰性发现即回收；后台每 10s 周期性清扫（`privacy.session.sweep-interval-ms` 可调），覆盖调用方中途断开、异常退出等再不来用的场景 |
| 查询处理异常 | 本次扣减回滚进会话额度（会话仍存活）或直接归还数据域（会话已并发关闭） |

归还之后同一数据域的其他请求**立即可用**；不会留下占着不放的死额度。
已花掉的部分在任何回收路径下都不退还。

### 兼容范围

- 老调用方不传 `sessionId`，请求方式与返回结构完全不变，仍从数据域账本直接扣减；
- 噪声派生逻辑未变：相同种子、相同输入（domain/groupKey/epsilon）的查询，
  带不带会话结果完全一致；
- 会话查询的 `budgetRemaining` 字段报告**会话内剩余额度**（老方式仍报告数据域剩余）。

## 接口

```
POST /api/query
{"domain":"health","groupKey":"age-30-40","epsilon":1.0,"delta":1e-5,"minGroupSize":5,"seed":42}
# 可选会话字段："sessionId":"<申请到的会话 id>"
```

响应：`{"status":"OK","value":...,"epsilonConsumed":1.0,"budgetRemaining":4.0,"reason":"OK"}`
或 `{"status":"REJECTED_*","value":null,"epsilonConsumed":0.0,"budgetRemaining":...,"reason":"..."}`。

```
POST /api/sessions                      # 申请会话额度
{"domain":"health","epsilon":2.0}       # 可选 "idleTimeoutMillis":30000
# -> {"status":"OK","sessionId":"...","domain":"health","epsilonGranted":2.0,...}
#    或 {"status":"REJECTED","reason":"申请额度 ... 超过当前可用预算 ..."}

DELETE /api/sessions/{sessionId}        # 主动结束，归还剩余额度
# -> {"status":"OK","sessionId":"...","epsilonReturned":1.5,...}

GET /api/budgets                        # 只读审计：所有数据域
GET /api/budgets/{domain}               # 只读审计：单个数据域（未知域 404）
# -> {"domain":"health","total":5.0,"consumed":1.0,"reserved":2.0,"available":2.0}
#    consumed=已真正花掉, reserved=被会话占着还没花, available=还能用
#    守恒不变式：consumed + reserved + available == total
```

本地模拟数据（固定种子生成，含 3 条记录的小分组 `health/rare-cohort` 用于触发匿名度拒绝）：

| domain | groupKey | 记录数 |
| --- | --- | --- |
| health | age-30-40 / age-40-50 / rare-cohort | 64 / 48 / 3 |
| finance | income-mid / income-high | 80 / 32 |

## 本地验证

```bash
mvn test          # 运行全部单元与集成测试
mvn spring-boot:run

# 老方式：直接扣数据域预算
curl -X POST localhost:8080/api/query -H 'Content-Type: application/json' \
     -d '{"domain":"health","groupKey":"age-30-40","epsilon":1.0,"delta":1e-5,"minGroupSize":5,"seed":42}'

# 会话额度：申请 -> 带 sessionId 查询 -> 主动结束
SID=$(curl -s -X POST localhost:8080/api/sessions -H 'Content-Type: application/json' \
      -d '{"domain":"health","epsilon":2.0}' | jq -r .sessionId)
curl -X POST localhost:8080/api/query -H 'Content-Type: application/json' \
     -d "{"domain":"health","groupKey":"age-30-40","epsilon":1.0,"delta":1e-5,"minGroupSize":5,"seed":42,"sessionId":"$SID"}"
curl -X DELETE localhost:8080/api/sessions/$SID

# 只读预算审计
curl -s localhost:8080/api/budgets/health | jq
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
- `SessionBudgetManagerTest`
  - 申请即占用、超额/越界/未知域申请被拒绝，审计视图守恒；
  - **32 线程并发消费同一会话额度**：恰好 5 次成功，累计消耗不超过申请量；
  - 主动结束归还剩余额度且幂等（重复归还不算多）；
  - 空闲超时自动回收、周期清扫只回收空闲会话、活跃会话保留；
  - 会话内回滚与超额回滚防护。
- `SessionQueryServiceTest`
  - 会话查询从会话额度内扣并报告会话剩余；
  - 过期/已结束/不存在/跨域会话一律 `REJECTED_SESSION_INVALID`，不返回值、不耗预算；
  - 会话额度耗尽返回 `REJECTED_BUDGET_EXHAUSTED`，与会话失效可区分；
  - 相同种子相同输入，带不带会话结果一致（兼容性）；
  - **32 线程并发会话查询**不超支；混合路径后审计数字守恒（已花+被占+可用==总额）；
  - 会话占满全域时其他请求被拒，归还后立即可用；
  - 前置校验失败（参数越界/匿名度不足）不消耗会话额度。
- `SessionApiTest`：REST 全生命周期（申请→审计→会话查询→结束→失效拒绝→守恒审计→老方式兼容），
  以及超额申请、未知会话、未知域审计 404。
