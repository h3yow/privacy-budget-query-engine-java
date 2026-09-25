# 差分隐私聚合查询引擎（本地模拟）

用内存数据模拟多个主体对同一数据域提交的差分隐私聚合统计请求。系统保证：

- 按请求声明的隐私参数（epsilon）消耗**按数据域隔离**的隐私预算；同域并发请求**不会超支**；
- 余额不足、隐私参数越界、匿名度不足都以**可区分的原因码**拒绝；
- 结果只返回**加噪后的扰动值**；拒绝/异常路径绝不返回原始聚合值；
- 噪声由显式种子确定性派生：**相同种子 + 相同输入 = 相同结果**，不同种子结果不同；
- 最小匿名度（k-匿名）前置校验失败时**回滚预占、不消耗预算**；任意时刻预算守恒。

## 1. 隐私参数语义

| 参数 | 语义 | 约束 |
| --- | --- | --- |
| `privacy.epsilon` | 本次请求消耗的隐私预算；越小隐私保护越强、噪声越大 | `0 < epsilon <= 1.0`（单请求上界 `PrivacyParams.MAX_EPSILON`） |
| `privacy.delta` | 松弛 DP 的失败概率；本实现仅支持**纯 DP**（拉普拉斯机制） | 必须 `= 0` |
| `sensitivity` | 聚合的 L1 灵敏度上界：`COUNT` 恒为 `1`；`SUM` 由请求方声明单条记录最大贡献绝对值 | `> 0` 且有限 |
| `minGroupSize` | 最小匿名度 k：每个分组原始记录数下限，任一分组 `n < k` 即拒绝 | `>= 1` |
| `seed` | 噪声确定性种子（`long`） | 任意值 |

噪声机制：对每个分组独立加 **拉普拉斯噪声** `Lap(b)`，尺度 `b = sensitivity / epsilon`。
分组噪声种子由 `(seed, 分组序号)` 经 SplitMix64 混合得到（分组按字典序稳定排序），
因此分组间噪声相互独立，同时整体结果仍可复现。

## 2. 预算与回滚规则

- 每个**数据域**一个预算账户，上界由配置 `dp.budget.limit-per-domain`（默认 `3.0`）决定，不同数据域互不影响。
- 账户用三阶段状态管理：`spent`（已永久消耗）与 `reserved`（已预占未决），任意时刻守恒：

  ```
  spent + reserved + remaining = limit
  ```

- 单次查询流程：

  1. **参数语义校验**（epsilon/delta/灵敏度/匿名度/数据域）——不触碰预算；
  2. **原子预占** `reserve(epsilon)`：在账户锁内完成“余额检查 + 扣减”。余额不足则账户无任何变化并拒绝；
  3. **最小匿名度校验**：任一分组记录数不足 → 抛出拒绝并在 `finally` 中 **rollback 原样归还预占**，`spent` 不变；
  4. 原始聚合 + 拉普拉斯扰动（原始值仅存在于服务内部，不进入返回体、不写日志）；
  5. **提交** `commit(epsilon)`：预占转为永久消耗。

- 拒绝原因码（HTTP 422，字段 `reason`）：

  | reason | 触发条件 | 是否消耗预算 |
  | --- | --- | --- |
  | `INVALID_EPSILON` | epsilon ≤ 0、NaN/Inf 或 > 单请求上界 | 否 |
  | `INVALID_DELTA` | delta ≠ 0 | 否 |
  | `INVALID_SENSITIVITY` | SUM 灵敏度 ≤ 0 或非有限 | 否 |
  | `INVALID_GROUP_SIZE` | minGroupSize < 1 | 否 |
  | `INVALID_DOMAIN` | 数据域未知 | 否 |
  | `BUDGET_EXHAUSTED` | 剩余预算（含预占）不足 | 否（原子预占失败，无部分扣减） |
  | `ANONYMITY_TOO_SMALL` | 某分组记录数 < minGroupSize | 否（预占已回滚） |

## 3. 接口

- `POST /api/query`：提交聚合请求；成功返回扰动结果，失败返回 422 + `reason`。
- `GET /api/budget/{domain}`：查看某数据域预算快照 `{domain, spent, limit, remaining}`。

预置本地数据：数据域 `sales`（分组 A=8 行、B=3 行、C=12 行）、`hr`（分组 X=5 行）。

请求示例：

```bash
curl -s -X POST http://localhost:8080/api/query \
  -H 'Content-Type: application/json' \
  -d '{
    "requesterId":"alice","domain":"sales","aggregationType":"COUNT",
    "groupColumn":"region","sumColumn":null,"sensitivity":1.0,
    "privacy":{"epsilon":0.5,"delta":0.0},
    "minGroupSize":1,"seed":42
  }'
```

成功响应（只含扰动值与可解释元数据，无任何原始值）：

```json
{
  "domain": "sales",
  "epsilonUsed": 0.5,
  "remainingEpsilon": 2.5,
  "results": [
    {"groupKey": "A", "value": 7.484089, "noiseScale": 2.0},
    {"groupKey": "B", "value": -1.665314, "noiseScale": 2.0},
    {"groupKey": "C", "value": 15.386496, "noiseScale": 2.0}
  ]
}
```

拒绝响应示例（HTTP 422）：

```json
{"rejected": true, "reason": "BUDGET_EXHAUSTED",
 "message": "数据域 [sales] 预算不足：剩余 0.000000（含预占），本次申请 0.100000，上界 3.000000"}
```

## 4. 可复现性约定

- 噪声样本 = `new Random(mix(seed, groupIndex))` 的首个均匀随机数经拉普拉斯逆 CDF 变换得到。
- 相同 `seed` 与相同请求（同域、同分组、同 epsilon/灵敏度）→ 逐分组结果完全一致；
  更换 `seed` → 噪声改变。注意：**预算消耗仍照常累计**，复现实验请使用独立数据域或重置账户。
- 噪声四舍五入保留 6 位小数，仅用于展示稳定，不改变机制的差分隐私性质。

## 5. 本地验证

### 运行测试

```bash
mvn test
```

覆盖的边界（共 14 个用例，见 `src/test/.../tests/`）：

- 成功路径、相同种子复现、不同种子产生差异、SUM 灵敏度 → 噪声尺度；
- epsilon / delta / sensitivity / minGroupSize / 数据域 越界拒绝且预算不触碰；
- 匿名度不足拒绝并回滚，随后同账户仍可正常使用（预算守恒、可复用）；
- 顺序消耗到预算不足：验证无重复扣减、无漏扣、无残留预占；
- **并发超支**：50 个线程同域各申请 0.2（上界 3.0），断言成功数**精确为 15**、其余全部 `BUDGET_EXHAUSTED`，
  最终 `spent=3.0 / remaining=0 / reserved=0`；
- HTTP 入口：成功体只含扰动值，拒绝体 422 + 可区分原因码且无 `results`。

测试中服务层会逐条打印**请求参数、预算变动与判定依据**，日志标记包括：
`[QUERY-START]`、`[BUDGET-BEFORE]`、`[BUDGET-RESERVED]`、`[ANONYMITY-FAIL]`、
`[BUDGET-COMMIT]`、`[BUDGET-ROLLBACK]`、`[NOISE]`、`[REJECT]`。

### 启动应用做手工验证

```bash
mvn spring-boot:run          # 默认随机端口，启动日志中查看 "Tomcat started on port"
# 或固定端口：在 src/main/resources/application.properties 中将 server.port 改为 8080
```

> 注：工程编译目标为 Java 21；如本机 JDK 较新（如 JDK 25），Spring Boot 3.4.1 内置
> ByteBuddy/Mockito 可能无法做字节码插装，因此 HTTP 测试采用手写桩而非 Mockito，
> 业务流程测试不依赖 Mockito，可直接运行。
