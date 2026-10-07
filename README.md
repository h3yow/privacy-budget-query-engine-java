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

## 会话额度

调用方可为某个数据域一次性申请一段**可重复使用的额度**，之后的查询携带 `sessionId`
从这段额度里扣，直到用完或主动结束，不必每次查询都盯域账本余额。

### 语义

- **申请**：`POST /api/session`，`{"domain":"health","epsilon":2.0,"idleTimeoutMillis":30000}`
  （`idleTimeoutMillis` 可空，缺省 30s）。申请量原子地从域**可用**预算转为**占用**，
  因此申请不会超过该域当前实际可用预算，并发申请也不会超占。
  参数非法 → `REJECTED_INVALID_PARAMETER`；可用不足 → `REJECTED_BUDGET_EXHAUSTED`。
- **消费**：查询请求带可选字段 `sessionId`（缺省/null 即走原来的单次扣预算路径，
  老调用方完全兼容）。会话内"检查余额 + 扣减"是原子操作，并发使用同一会话不会重复花，
  累计消耗不超过申请量；每次消费实时把域账本中的占用转为实耗。
- **失效拒绝**：会话不存在、已结束或已过期时再查询，返回
  `REJECTED_SESSION_INVALID`（与参数越界、匿名度不足、预算耗尽均可区分），
  `value == null`，不消耗任何预算，绝不返回原始或扰动结果。
- **结果一致性**：噪声只由 `seed + domain + groupKey` 派生，相同种子、相同输入的查询
  无论走不走会话，结果完全一致。

### 生命周期与回收

```
ACTIVE --主动结束(DELETE /api/session/{id})--> CLOSED
ACTIVE --空闲超时(定时回收 或 访问时惰性发现)--> EXPIRED
```

- 空闲超时以**最近一次使用时间**起算（申请后一直没人用也会到期）；
  使用方中途断开、处理异常不再来请求时，由定时回收（每秒）兜底。
- 状态迁移只发生一次，迁移时把未花完的部分一次性归还域账本：
  **不会重复归还**，也**不会留下占着不放的死额度**；归还后同域其他请求立即可用。
- 扰动阶段异常时，当次扣减回滚为会话占用（不丢不重）。

### 预算审计

`GET /api/budget/{domain}` 返回只读视图：

```json
{"domain":"health","total":5.0,"consumed":1.5,"reserved":2.0,"available":1.5}
```

恒有 `consumed + reserved + available == total`：
`consumed` 是真正花掉的，`reserved` 是被会话占着还没花的，`available` 是还能用的。
`GET /api/session/{sessionId}` 可分辨单笔额度的状态（`granted` / `consumed` / `held` /
`ACTIVE|CLOSED|EXPIRED`）。

## 接口

```
POST /api/query
{"domain":"health","groupKey":"age-30-40","epsilon":1.0,"delta":1e-5,"minGroupSize":5,"seed":42}
# 可选："sessionId":"<会话ID>" 表示从会话额度扣费
```

响应：`{"status":"OK","value":...,"epsilonConsumed":1.0,"budgetRemaining":4.0,"reason":"OK"}`
或 `{"status":"REJECTED_*","value":null,"epsilonConsumed":0.0,"budgetRemaining":...,"reason":"..."}`。
（`budgetRemaining`：直接路径为域账本剩余，会话路径为会话剩余额度。）

```
POST   /api/session          # 申请会话额度
GET    /api/session/{id}     # 查看单笔额度状态
DELETE /api/session/{id}     # 主动结束并归还未用额度
GET    /api/budget/{domain}  # 预算审计：total/consumed/reserved/available
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
curl -X POST localhost:8080/api/query -H 'Content-Type: application/json' \
     -d '{"domain":"health","groupKey":"age-30-40","epsilon":1.0,"delta":1e-5,"minGroupSize":5,"seed":42}'

# 会话额度全流程
SID=$(curl -s -X POST localhost:8080/api/session -H 'Content-Type: application/json' \
      -d '{"domain":"health","epsilon":2.0}' | jq -r .sessionId)
curl -X POST localhost:8080/api/query -H 'Content-Type: application/json' \
     -d "{\"domain\":\"health\",\"groupKey\":\"age-30-40\",\"epsilon\":1.0,\"delta\":1e-5,\"minGroupSize\":5,\"seed\":42,\"sessionId\":\"$SID\"}"
curl localhost:8080/api/budget/health        # 审计：consumed+reserved+available == total
curl -X DELETE localhost:8080/api/session/$SID   # 主动结束，未用额度归还
```

测试覆盖（日志会打印请求参数、预算变动与判定依据）：

- `AggregationQueryServiceTest`
  - 相同种子/输入结果一致，不同种子产生差异；
  - 参数越界（epsilon/delta/未知域）拒绝且预算守恒；
  - 匿名度不足拒绝且不消耗预算；
  - 预算耗尽拒绝且原因可区分；
  - **32 线程并发争抢 5.0 预算**：恰好 5 次成功，消耗合计 + 剩余 == 总预算（无重复扣减、无漏扣）；
  - 混合成功/失败路径后的预算守恒。
- `BudgetSessionTest`
  - 会话申请占用域预算、超额/非法申请可区分拒绝；
  - **32 线程并发消费同一会话**：恰好花完申请量，不重复花、不超支；
  - 空闲超时（定时回收与访问时惰性发现）自动归还未用额度，同域请求立即可用；
  - 主动结束归还且幂等，不重复归还、不留死额度；
  - 失效会话查询返回 `REJECTED_SESSION_INVALID`，不返回任何结果值、不消耗预算；
  - 扰动异常路径对会话与直接扣减都完整回滚；
  - 混合路径后审计守恒：`consumed + reserved + available == total`；
  - 兼容性：相同种子/输入，走不走会话结果一致。
- `QueryControllerTest`：REST 入口下的成功、预算耗尽、参数越界与匿名度不足路径。
- `SessionControllerTest`：REST 下会话申请 → 会话查询 → 审计 → 主动结束 →
  失效拒绝的全生命周期，以及申请校验与未知域审计 404。
