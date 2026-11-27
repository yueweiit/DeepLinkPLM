# PLM–CRM 实物排程与手机型号接口文档

> 后续实施已完成源码及隔离测试。本文保留为原始设计记录，对接请使用 [最终接口文档 v3.0](./PLM-CRM-实物排程与手机型号最终接口文档-v3.0.md)，实际修改与测试见 [实施报告](./PLM-CRM-实物排程与手机型号实施与测试报告-v3.0.md)。以下“尚未实现”等文字描述的是本设计稿编写时点。

| 项目 | 内容 |
| --- | --- |
| 版本 / 日期 | v3.0 / 2026-11-09 |
| 业务依据 | 「开模时间管控 → 实物排程」截图，以及用户对三个回传日期和手机型号查询的确认 |
| 提供方 / 调用方 | PLM 提供接口，CRM 授权调用 |
| 状态 | 业务方向及字段权限已确认；协议细节为拟定契约，待双方技术联调确认，尚未实现 |
| 替代关系 | 替代旧 v1.0 推送方案及 v2.0 申请维度方案；旧代码改造方案不能直接作为本版实施依据 |

本次只编写接口文档，不修改业务代码、数据库、配置或运行服务。接口路径、鉴权、对外聚合版本、幂等及变更日志均为待开发能力，不能据此宣称当前环境已经支持。示例数据为虚构。

## 1. 已确认的需求基线

1. CRM 通过独立凭证查询 PLM，不要求 PLM 主动推送整表，也不要求 CRM 提供接收通知的 HTTP 地址。
2. 排程查询以实物模具套为单位，一行一套，覆盖全部产品范围内的当前数据；不是按开模申请单查询。
3. CRM 只回写三个日期：**实际抵墨、计划验收、计划试模量产**。允许单独修改及明确清空。其余表格业务数据和编码由 PLM 提供，CRM 只读。
4. PLM 内部仍允许在现有权限和业务规则内人工修改这三个日期。CRM 与 PLM 都不能无条件覆盖对方刚保存的数据。
5. PLM 保存回传值后重新计算相关状态、待办及延期，CRM 使用 PLM 返回的正式结果。
6. 提供独立手机型号查询，包含尚无开模申请或模具的新型号及其型号编码。
7. CRM 不需要历史版本、操作历史、过往变更回放。本版将其解释为：首次同步当前有效数据，包括接入前已创建但当前仍有效的记录；不迁移过往版本或已删除档案。这与“只要接入后新建数据”不同。
8. 删除、停用的通知方式采用本文建议：CRM 轮询增量接口，接收明确生命周期标记；恢复也产生变更。该方式及建议时限属于本版拟定协议。

“全部数据”限定为本接口的实物排程和手机型号数据，不代表授予 CRM 用户管理、费用、附件、数据库或其他系统管理权限。截图中的「操作」按钮不作为业务字段外传。

## 2. 记录身份与范围

### 2.1 实物模具套

- `sourceSystem="PLM"`，`entityType="MOLD_SET"`，`recordId` 为 `moldSetId` 的字符串形式。
- CRM 按 `(sourceSystem, entityType, recordId)` 保存，不能使用型号名称、款式、实物展示编号或完整模具编码替代此唯一键。
- 同款同型号可以有多套实物；PC、TPU 是同套的部件，共模适用多个型号也不复制实物套。
- 同套被多张申请引用时仍只有一条实物排程；不通过申请 ID 回写日期。
- 未建立实物套的申请不混入本查询。其新手机型号仍由独立型号接口提供。

### 2.2 手机型号

- `entityType="PHONE_MODEL"`，`recordId` 为 `phoneModelId` 的字符串形式。
- 新增标准型号保存后即可查询，不依赖是否有产品、申请、模具或 SKU。
- 手机编码为空时仍返回型号；以后分配或修正编码产生变更，CRM 按同一 ID 更新，不能重新创建同名型号。

### 2.3 生命周期

对外统一使用 `lifecycleStatus`：`ACTIVE`（当前可用）、`INACTIVE`（明确停用）、`DELETED`（已删除，仅变更标记返回）。详情和当前列表仅返回未删除的当前对象；列表默认仅 ACTIVE，可用 `includeInactive=true` 查询当前停用对象。

生命周期须映射已有正式的删除、主数据停用及有效性规则；实现时逐项落到源字段，不按空日期、任务逾期或无编码推断停用。模具「暂停执行」「待开工」「交付完成」属于业务状态，不自动等同于 INACTIVE，也不因交付完成而从 CRM 移除。

父档停用或删除确实导致子对象不可用时，应为受影响对象生成相应变更。手机型号停用不自动删除或停用相关模具套；已有引用保留，并更新关联型号的当前有效性。

## 3. 接口及公共约定

基础路径拟定为 `/api/integrations/v3/crm`，独立于老页面接口和旧推送接口。

| 方法 | 相对路径 | 用途 |
| --- | --- | --- |
| GET | `/mold-schedules` | 全部产品范围的实物排程分页查询 |
| GET | `/mold-schedules/{moldSetId}` | 单套最新排程、状态及版本 |
| PATCH | `/mold-schedules/{moldSetId}/crm-dates` | CRM 三日期局部回写及清空 |
| GET | `/phone-models` | 独立手机型号分页查询 |
| GET | `/phone-models/{phoneModelId}` | 单个型号及当前编码 |
| GET | `/changes` | 两类资源的新增、变更、删除、停用、恢复索引 |

| 项目 | 拟定约定 |
| --- | --- |
| 通信 | HTTPS；UTF-8；请求和响应为 `application/json` |
| 认证 | `Authorization: Bearer <crm-service-token>`，独立服务凭证，安全配置发放及轮换 |
| 权限 | `crm:mold-schedule:read`、`crm:phone-model:read`、`crm:schedule-dates:write`；服务主体业务范围配置为全部，不借用管理员个人会话 |
| 日期 | `YYYY-MM-DD`，不含时刻，不进行跨时区换日 |
| 时间戳 | ISO 8601 且带时区，例如 `2026-11-09T14:00:00+08:00` |
| 业务时区 | `Asia/Shanghai` |
| ID / 编码 | 字符串；防止大整数精度损失及编码前导零丢失 |
| 缺失值 | 约定字段无值返回 null；无关联返回空数组，不能编造值 |
| 跟踪 | 响应头返回 `X-Request-Id`，错误体同时返回 `requestId` |

凭证只有所需读写权限；不允许通过本接口新建型号、分配编码、删除/停用档案、修改负责人或其他日期。旧出站 HMAC 不等于本版入站鉴权已实现。

## 4. 排程查询字段

列表每条记录与详情 `data` 使用相同字段结构。三个回传字段也在查询中返回当前正式值，方便 CRM 核对，包括 PLM 人工修订后的结果。

| 页面/用途 | JSON 字段 | 类型 | CRM 权限 |
| --- | --- | --- | --- |
| 身份 | `sourceSystem`、`entityType`、`recordId`、`moldSetId` | string | 只读 |
| 内部主档关联 | `itemId` | string/null | 只读，不作回写目标 |
| 款式 | `styleName` | string/null | 只读；与页面款式展示口径一致 |
| 产品及产品编码 | `products` | array | 只读；各项 `productId`、`productCode`、`productName`，仅已确认关系 |
| 本套适用手机 | `compatiblePhones` | array | 只读，见 4.1 |
| 本套适用手机展示文字 | `compatiblePhonesText` | string | 只读，保留“待核对/适配待确认”等提示，不能作为身份键 |
| 实物编号 | `assetNumber` | string | 只读，例如 `MS-000597` |
| 套次 | `setNo` | integer | 只读，不拼入唯一键 |
| 模具负责人 | `owner` | object/null | 只读；`userId`、`displayName`，不外传账号联系方式等无关信息 |
| 开模日期 | `dates.openedDate` | date/null | 只读 |
| 计划模具交付 | `dates.plannedMoldDeliveryDate` | date/null | 只读 |
| 实际发模 | `dates.actualShipDate` | date/null | 只读 |
| 计划到墨工厂 | `dates.plannedFactoryArrivalDate` | date/null | 只读 |
| **实际抵墨** | **`dates.actualMexicoArrivalDate`** | **date/null** | **允许回写及清空** |
| **计划验收** | **`dates.plannedAcceptanceDate`** | **date/null** | **允许回写及清空** |
| **计划试模量产** | **`dates.plannedProductionDate`** | **date/null** | **允许回写及清空** |
| 交付状态 | `deliveryStatus`、`deliveryStatusText` | string | 只读，PLM 计算的页面有效状态 |
| 延期 | `overdue`、`overdueTasks` | boolean / array | 只读；是否有逾期节点及节点明细，见第 7 节 |
| 完整模具编码 | `moldParts` | array | 只读；`partType`、`materialCode`、`moldCode` |
| 生命周期 | `lifecycleStatus` | string | 只读 |
| 聚合版本 | `recordVersion` | string | 只读，回写时通过 If-Match 原样带回 |
| 接口数据更新时间 | `updatedAt` | timestamp | 只读，关联变化也应计入，不只看模具套自身 updated_at |
| 状态计算业务日期 | `asOfDate` | date | 只读，明确逾期计算日 |
| 当前可写字段 | `writableFields` | string[] | 服务端根据状态及权限返回实际允许的三个日期键或其子集 |

首期不外传整份表单、附件、费用、完整人员信息、历史版本，也不额外扩展 SKU。截图折叠区以外的 PC/TPU 实际验收日期等仍可供 PLM 内部计算状态，但不因此开放 CRM 写权限。

### 4.1 型号关联及编码

`compatiblePhones` 每项包含 `phoneModelId`、`brandName`、`modelName`、`phoneModelCode`、`lifecycleStatus`、`relationStatus`。`relationStatus` 拟定为：

| 值 | 含义 |
| --- | --- |
| `CONFIRMED` | 本套关系和 PC/TPU 适配均按现有依据确认 |
| `PENDING_CONFIRMATION` | 已有套级关联，但适配依据或部件确认尚未齐全 |
| `APPLICATION_SOURCE` | 仅有来源申请型号，不宣称已经确认本套适配 |

纯历史自由文本且没有标准 ID 时，只进入 `compatiblePhonesText`，不编造标准型号身份。标准手机编码未分配时返回 null，不能用历史申请码代替。共模情况下实物原刻码可能与兼容型号编码不同，应保留各自原值，不重写实物编码。

遵循仓库统一编码规则：`MBA101199 = MBA（产品）+ 10（材质）+ 1199（机型）`；TPU=`10`、PC=`20`；手机码始终为四位字符串，例如 `0185`。完整码提取和校验复用 `ProductBusinessCodeGenerator`，不按名称猜码、补零或宽松截码。`MBA10`、`LLT10` 等历史目录前缀不是完整模具编码；没有正式完整码时返回 null，必要展示原值须另设字段，不混入完整码。

### 4.2 详情示例

`GET /api/integrations/v3/crm/mold-schedules/597`

响应 `200 OK`，响应头 `ETag: "ms-example-17"`：

```json
{
  "data": {
    "sourceSystem": "PLM",
    "entityType": "MOLD_SET",
    "recordId": "597",
    "moldSetId": "597",
    "itemId": "300",
    "styleName": "亮甲2.0",
    "products": [
      {"productId": "35", "productCode": "MBA", "productName": "亮甲2.0"}
    ],
    "compatiblePhones": [
      {
        "phoneModelId": "88",
        "brandName": "示例品牌",
        "modelName": "示例型号",
        "phoneModelCode": "1199",
        "lifecycleStatus": "ACTIVE",
        "relationStatus": "PENDING_CONFIRMATION"
      }
    ],
    "compatiblePhonesText": "示例品牌 示例型号 · 1199（适配待确认）",
    "assetNumber": "MS-000597",
    "setNo": 1,
    "owner": {"userId": "12", "displayName": "示例负责人"},
    "dates": {
      "openedDate": "2026-09-29",
      "plannedMoldDeliveryDate": "2026-10-20",
      "actualShipDate": "2026-10-23",
      "plannedFactoryArrivalDate": "2026-10-27",
      "actualMexicoArrivalDate": "2026-10-27",
      "plannedAcceptanceDate": "2026-10-28",
      "plannedProductionDate": "2026-10-31"
    },
    "deliveryStatus": "mexico_arrived",
    "deliveryStatusText": "抵达墨西哥",
    "overdue": true,
    "overdueTasks": [
      {
        "taskKey": "factory_arrival",
        "title": "确认 PC / TPU 实际到厂",
        "dueField": "plannedFactoryArrivalDate",
        "dueDate": "2026-10-27",
        "overdueDays": 13
      },
      {
        "taskKey": "acceptance",
        "title": "完成 PC / TPU 验收",
        "dueField": "plannedAcceptanceDate",
        "dueDate": "2026-10-28",
        "overdueDays": 12
      },
      {
        "taskKey": "production",
        "title": "填写试模量产",
        "dueField": "plannedProductionDate",
        "dueDate": "2026-10-31",
        "overdueDays": 9
      }
    ],
    "moldParts": [
      {"partType": "TPU", "materialCode": "10", "moldCode": "MBA101199"},
      {"partType": "PC", "materialCode": "20", "moldCode": "MBA201199"}
    ],
    "lifecycleStatus": "ACTIVE",
    "recordVersion": "ms-example-17",
    "updatedAt": "2026-11-09T14:00:00+08:00",
    "asOfDate": "2026-11-09",
    "writableFields": [
      "actualMexicoArrivalDate", "plannedAcceptanceDate", "plannedProductionDate"
    ]
  }
}
```

## 5. 列表、筛选与首次同步

### 5.1 排程列表

`GET /api/integrations/v3/crm/mold-schedules`

| 参数 | 说明 |
| --- | --- |
| `pageSize` | 默认 50，范围 1–100 |
| `pageToken` | 不透明的下一页令牌，第一页不传 |
| `moldSetId`、`assetNumber` | 精确筛选实物 |
| `productCode`、`phoneModelId`、`phoneModelCode`、`moldCode` | 已确认数据关系的精确筛选，未知或空编码不猜测；型号筛选基于本套明确关联，不以展示文字代替 |
| `deliveryStatus` | 按第 7 节枚举筛选 |
| `overdueOnly` | 默认 false，仅返回有逾期节点的记录 |
| `keyword` | 款式、型号展示文字、实物编号搜索，不作为唯一关联依据 |
| `includeInactive` | 默认 false；true 包含当前已停用但未删除记录 |

多个筛选条件按 AND 组合，全部产品范围不等于忽略调用方传入的筛选条件。后续翻页必须保持相同条件、账号及页大小。

所有列表使用以下响应结构，`items` 分别放排程或手机型号对象：

```json
{
  "data": {
    "items": [],
    "hasMore": false,
    "nextPageToken": null,
    "syncCursor": "checkpoint-example"
  }
}
```

### 5.2 首次同步当前数据，不迁移历史

1. CRM 不带业务筛选，先读取模具排程第一页。PLM 在读数据前取全局变更起点 S0，并固定模具扫描最大 ID。首次响应返回 `syncCursor=S0`。
2. CRM 分页扫描全部当前 ACTIVE 模具，再分页扫描全部当前 ACTIVE 手机型号。两类记录都按各自数值 ID 升序游标翻页；期间按唯一键保存，不按名称合并。
3. 两类扫描全部完成后，使用最初 S0 读取统一变更接口，补偿扫描期间发生的新增、修改、删除、停用、恢复；其他列表返回的起点不替换 S0。
4. 初次接入不读取 S0 以前的变更记录，不提供已删除历史对象、旧版快照和历史操作列表。当前仍有效的老记录属于当前数据，正常返回。

分页读取是实时数据，不宣称多页来自同一个数据库快照。每类扫描上界和游标保证稳定遍历，上界外的新记录由 S0 后增量补入。日志及起点机制必须保证事务提交顺序不造成遗漏。完成一次完整扫描和增量补偿后，才能宣称已完成同步。

列表只有在无业务筛选且 `includeInactive=false` 的完整扫描模式下提供 `syncCursor`，其他查询返回 null。`pageToken` 拟定有效期 24 小时，失效返回 410 并重启扫描；不得把当前页没看到某条记录当成其已删除。

## 6. 三个日期回写接口

`PATCH /api/integrations/v3/crm/mold-schedules/{moldSetId}/crm-dates`

头部要求：

```http
Authorization: Bearer <crm-service-token>
Content-Type: application/json
If-Match: "ms-example-17"
Idempotency-Key: crm-schedule-597-20261109-001
```

请求示例：

```json
{
  "dates": {
    "actualMexicoArrivalDate": "2026-11-09",
    "plannedAcceptanceDate": "2026-11-11",
    "plannedProductionDate": "2026-11-14"
  },
  "reason": "CRM 已确认实际抵墨并调整后续计划",
  "crmReference": "CRM-SCHEDULE-0001",
  "operator": {"id": "crm-user-100", "name": "示例操作人"}
}
```

清空示例，保持另外两个日期不变：

```json
{
  "dates": {
    "plannedAcceptanceDate": null
  },
  "reason": "原验收计划取消，待重新确认",
  "crmReference": "CRM-SCHEDULE-0002"
}
```

### 6.1 写入规则

- `dates` 必填且非空，仅接受 `actualMexicoArrivalDate`、`plannedAcceptanceDate`、`plannedProductionDate`，值只能是合法日期字符串或 null。
- 未出现的日期键保持原值；null 明确清空；空字符串不是清空指令。允许一次清空三项，但不自动填入建议日期。
- `reason` 必填、去除首尾空白后非空，建议上限 2000 字符。首次填写、改值和清空均记录来源与原因。
- `crmReference` 必填，为 CRM 本次业务操作引用，建议上限 128 字符；不替代幂等键。`operator` 可选，仅辅助审计，不作为认证身份。
- 路径 ID 是唯一目标，禁止按传入名称或编码另找对象。未知字段、只读字段、非法值一律拒绝，不能忽略后返回成功。
- 每次写入在单一事务内校验并保存：三个字段全部成功或全部回滚；沿用现有业务规则，不绕过部件、执行、日期及主档有效性约束。
- 停用、删除对象不能写入。现有暂停规则仍生效：若暂停时只允许补录实际日期，则禁止修改/清空两个计划日期；`writableFields` 相应缩减，服务端保存时再次校验。
- 修改或清空计划日期不自动生成/替换已留存的计划基准，不把预测或建议日期当作确认计划。需核对基准时沿用原业务提示。
- 如果提交值均未变化，返回当前详情，不制造重复业务变更，但仍保存本次幂等结果。

### 6.2 PLM 人工修订及并发

三个日期允许 PLM 用户按现有权限修改。双方都写入同一正式业务字段，PLM 人工修订同样影响接口版本及增量，不另存一份互不一致的 CRM 副本。

`recordVersion` 为对外不透明聚合版本，不与申请版本、计划基准版本、节点版本或旧推送版本混用。详情头部 ETag 为该版本加双引号的形式；CRM 写入必须携带刚查询到的 If-Match，不接受 `*`。

版本涵盖回写目标及依赖，包括三个日期、执行/有效性状态，以及接口投影相关关系。读取数据与版本保持一致；保存时原子校验版本、权限和当前状态，不能仅先查询后直接覆盖。PLM 页面或其他入口已改变相关数据时，旧版本返回 `412 VERSION_CONFLICT`，无字段写入。CRM 重新查询并由业务核对后，以新版本及新幂等键提交，不盲目自动覆盖。

### 6.3 幂等和保存结果

`Idempotency-Key` 必填，建议 1–128 字符。同服务主体的一个键绑定方法、路径、If-Match 和规范化请求内容；同键同内容重放原成功结果，同键不同内容返回 `409 IDEMPOTENCY_KEY_REUSED`。保留期拟定至少 7 天。

服务端先校验当前服务身份和读取权限，再查找已提交的幂等结果；命中后在新请求的版本检查前返回原成功结果，避免第一次保存导致版本变化后重试失败。业务保存和成功幂等结果须同一事务提交，同键并发不能重复执行。凭证已撤销或读取权限已收回时不得因命中缓存绕过权限。

成功返回 `200` 和第 4.2 节同结构的保存后详情及新 ETag。超时或 5xx 不代表业务未提交，CRM 保留原请求、原版本和原幂等键重试，不能直接换新键重复写入。幂等结果可能比当前记录旧，需要最新数据时再 GET；超过幂等保留期先查询和核对，不无条件重放。

## 7. 自动状态与延期

PLM 保存三个日期后，返回与页面相同口径的有效状态。保存日期不是接收任意状态指令；CRM 不得提交 `deliveryStatus`、`overdue`、PC/TPU 验收结果或量产实际结果。

| 状态值 | 中文含义 |
| --- | --- |
| `pending` | 待开模 |
| `making` | 制作中 |
| `ready` | 待发模 |
| `shipped` | 已发出 |
| `mexico_arrived` | 抵达墨西哥 |
| `partially_arrived` | 部分到厂 |
| `awaiting_acceptance` | 已到厂，待验收 |
| `partially_accepted` | 部分验收 |
| `acceptance_failed` | 验收不通过 |
| `delivered` | 交付完成 |
| `market_ready` | 已确认上市 |
| `paused` | 已暂停，保留已有进度事实 |
| `planned` | 待开工，尚未进入实际开工 |

后两个为页面有效状态的对外归一化表达，实现时从现有暂停、待开工标志映射，不擅自改写内部状态码。有效状态的覆盖优先级与页面一致：暂停、待开工优先展示，其后为正式排程状态。

具体效果：

- 实际抵墨填写后，若无暂停、待开工或更后续/异常状态，显示抵达墨西哥；已有到厂、验收等更后续事实时不能强制退回抵墨。
- 实际抵墨清空后重新计算，不默认退回“已发出”，也不自动删除后续实际事实。
- 填写计划验收、计划试模量产只改变计划和相关待办/延期判断，不标记实际完成。
- PC、TPU 实际到厂和验收通过等交付条件仍沿用现有业务规则，不因抵墨或计划日期存在就交付完成。
- `overdue` 表示当前是否存在已过计划期限且尚未完成的节点，`overdueTasks` 给出节点、计划日期和逾期天数；无逾期时为 false 和空数组。
- 清空计划不等于节点已完成。没有有效计划期限的节点不凭建议日期判逾期，仍按 PLM 原规则形成缺填待办。
- 超期按 Asia/Shanghai 的业务日期计算，不由 CRM 自行推断。每日日期推进引起对外延期值变化时，亦须产生增量。

## 8. 独立手机型号查询

`GET /api/integrations/v3/crm/phone-models`

支持 `pageSize`、`pageToken`、`phoneModelId`、`phoneModelCode`、`brandId`、`keyword`、`includeInactive`。分页规则同第 5 节，多个筛选按 AND 组合。编码精确匹配，名称用于模糊搜索；首期不支持 CRM 修改手机型号主数据。

`GET /api/integrations/v3/crm/phone-models/{phoneModelId}` 返回当前详情，含字段：

| 字段 | 含义 |
| --- | --- |
| `sourceSystem` / `entityType` / `recordId` | PLM / PHONE_MODEL / 手机型号 ID 字符串 |
| `phoneModelId` | 标准手机型号的稳定 ID |
| `brandId` / `brandName` | 品牌 ID 及名称 |
| `modelName` | 标准型号名称 |
| `phoneModelCode` | 标准型号编码字符串；尚未分配时 null |
| `lifecycleStatus` | ACTIVE 或 INACTIVE |
| `recordVersion` | 型号当前投影版本，不与模具版本混用 |
| `createdAt` / `updatedAt` | 创建时间、接口数据最近更新时间 |

响应示例 `200 OK`：

```json
{
  "data": {
    "sourceSystem": "PLM",
    "entityType": "PHONE_MODEL",
    "recordId": "188",
    "phoneModelId": "188",
    "brandId": "10",
    "brandName": "示例品牌",
    "modelName": "新型号示例",
    "phoneModelCode": "0185",
    "lifecycleStatus": "ACTIVE",
    "recordVersion": "pm-example-2",
    "createdAt": "2026-11-09T09:00:00+08:00",
    "updatedAt": "2026-11-09T10:00:00+08:00"
  }
}
```

未编码的新型号同样返回上述结构，只是 `phoneModelCode=null`；正式编码后更新同一记录并产生 UPSERT。标准码不可用时不拿历史模具刻码或申请码填充。型号名称、品牌、编码、生命周期变化时，也须更新受影响的模具排程投影。

## 9. 删除、停用及其他增量通知

### 9.1 通知方式

CRM 定期调用 `GET /api/integrations/v3/crm/changes?cursor={cursor}&pageSize=100`。`cursor` 必填，来自首次同步 S0 或上一页 `nextCursor`；默认页大小 100，范围 1–100。

拟定每 60 秒轮询一次，`hasMore=true` 时继续取下一页。没有变化也返回有效 `nextCursor`。本版是 CRM 拉取变更，不是 Webhook；在网络和服务正常时，变化可在下一轮刷新中被获取，不承诺实时送达。

| action | PLM 发生的变化 | CRM 的处理 |
| --- | --- | --- |
| `UPSERT` | 新增、改值、关联资料改变、状态或延期变化 | 查询最新详情，按唯一键新增或更新 |
| `DISABLE` | 正式停用或相关主档规则导致不可用 | 标记停用，从当前可选列表隐藏，禁止回写；保留已有业务引用 |
| `DELETE` | 已删除；只返回必要标记 | 从有效展示移除、禁止回写；不要求级联删除 CRM 已有订单等业务历史 |
| `ENABLE` | 恢复启用或正式恢复删除后的可用记录 | 重新 GET 最新详情并恢复使用；同 ID 更新，不按名称新建 |

DELETE 标记必须在业务删除及关联清理前保留稳定 ID，并在业务事务提交后对增量可见；即使物理记录已不在，也能告知 CRM 删除。不能靠“列表中消失了”代替通知。停用前的日期值、删除前的整行快照不返回。

### 9.2 增量示例

```json
{
  "data": {
    "changes": [
      {
        "changeId": "90001",
        "entityType": "MOLD_SET",
        "recordId": "597",
        "action": "UPSERT",
        "lifecycleStatus": "ACTIVE",
        "changedAt": "2026-11-09T14:01:00+08:00"
      },
      {
        "changeId": "90002",
        "entityType": "PHONE_MODEL",
        "recordId": "188",
        "action": "DISABLE",
        "lifecycleStatus": "INACTIVE",
        "changedAt": "2026-11-09T14:02:00+08:00"
      },
      {
        "changeId": "90003",
        "entityType": "MOLD_SET",
        "recordId": "600",
        "action": "DELETE",
        "lifecycleStatus": "DELETED",
        "changedAt": "2026-11-09T14:03:00+08:00"
      }
    ],
    "hasMore": false,
    "nextCursor": "checkpoint-next-example"
  }
}
```

### 9.3 一致性和失败处理

1. 一个全局游标消费两种实体，实体 ID 必须与 `entityType` 一起解释；不通过关键词或局部业务筛选消费全局同步流。
2. 增量以可靠的事务提交可见顺序形成顺序水位。数据库序列分配顺序不等于提交顺序，不直接用简单自增号或 `updatedAt > 上次时间` 宣称不会漏数据。
3. 每轮固定读取上界，后续页沿用，新增变更进入下一轮。CRM 按 changeId 顺序落地，本页成功后才提交 nextCursor；失败使用旧游标重读，允许至少一次投递、重复处理。
4. UPSERT/ENABLE 后详情返回当前状态，不是该事件发生时快照。若已停用，则按详情标记停用；若已删除返回 404，则移除有效展示。后续 DELETE/DISABLE 通知仍可重复应用。
5. DELETE/DISABLE 之后可能紧接恢复；CRM 必须继续消费后续事件，不能并发乱序覆盖。若并发查询详情，须串行处理同一资源或防止较早请求的响应覆盖较新处理结果。
6. 覆盖 PLM 页面、批量保存、导入、型号新建/分码/修改、生命周期、CRM 回写及所有相关正式写入入口；不能只在一个保存按钮处登记。
7. 共模关系、部件、编码、型号、品牌、负责人显示名等变化影响对外字段时，为所有受影响实物产生 UPSERT；自然日变更引起延期结果变化也纳入。

### 9.4 不需要历史与日志保留的关系

本接口不提供历史业务快照或审计历史。为保证 CRM 离线后仍能获知删除，PLM 仍需保留近期同步变更标记，属于传输可靠性记录，不是要求 CRM 迁移历史数据，也不删除 PLM 原有审计历史。

建议变更日志和游标有效期为 **30 天**；实际期限在联调容量评估后配置确认。CRM 超过可追溯时间未同步时返回 `410 CURSOR_EXPIRED`，不能悄悄从“现在”继续而漏掉删除。

游标过期后的处理：CRM 开始一轮新的完整当前数据扫描并取新起点；旧副本先标记待核对，不立即清空。两类当前数据扫描及增量补偿全部成功后，将本轮未出现的旧记录移出有效展示。本方法只清理同步副本的可用性，不删除 CRM 的业务单据。扫描失败或任一页未完成时，不允许执行批量清理。

## 10. 错误与异常处理

错误使用真实 HTTP 失败状态，不以 200 包装失败：

```json
{
  "error": {
    "code": "VERSION_CONFLICT",
    "message": "记录已更新，请重新查询核对后提交",
    "fields": []
  },
  "requestId": "req-example-001"
}
```

| HTTP | 错误码示例 | 说明 |
| --- | --- | --- |
| 400 | `INVALID_ARGUMENT` | 参数、未知字段、空补丁、日期格式错误或缺幂等键 |
| 401 | `UNAUTHENTICATED` | 凭证无效、过期或撤销 |
| 403 | `FORBIDDEN` / `FIELD_NOT_WRITABLE` | 没有写权限或字段不允许回写 |
| 404 | `NOT_FOUND` | 不存在或已删除；不泄露权限外记录 |
| 409 | `RESOURCE_INACTIVE` / `EXECUTION_RESTRICTED` | 停用、执行状态或当前业务前置条件阻止修改 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 一个幂等键用于不同请求 |
| 410 | `CURSOR_EXPIRED` / `PAGE_TOKEN_EXPIRED` | 重新完整同步或重启扫描 |
| 412 | `VERSION_CONFLICT` | 并发冲突，无字段写入 |
| 422 | `BUSINESS_RULE_VIOLATION` | 合法格式但违反现有业务校验，返回可公开的字段原因 |
| 428 | `PRECONDITION_REQUIRED` | 缺少 If-Match |
| 429 / 503 | `RATE_LIMITED` / `TEMPORARILY_UNAVAILABLE` | 按 Retry-After 退避重试 |
| 500 | `INTERNAL_ERROR` | 未处理异常，带跟踪 ID，不泄露 SQL、堆栈或敏感字段 |

校验失败及版本冲突不自动强制重试；暂时性错误按原幂等键退避。停用详情仍可返回 200 及 INACTIVE，用于确认当前状态；停用不是已删除，日期 PATCH 返回 409。

## 11. 调用链路及代码边界（非实施清单）

```text
CRM 独立服务凭证
  ├─ 实物排程列表/详情 → PLM 新增查询投影 → 当前正式数据、状态、编码
  ├─ 手机型号列表/详情 → PLM 标准手机主数据 → 无模具的新型号也可查
  ├─ 三日期 PATCH → 白名单/权限 → 幂等 → 原子版本与状态检查
  │                → 现有正式保存及业务校验 → 审计/变更记录 → 保存后详情
  └─ 统一增量轮询 → UPSERT/ENABLE 后查详情；DELETE/DISABLE 停止使用

PLM 人工修改/导入/主数据变化/日期推进 → 同一正式数据与增量机制 → CRM 下次刷新
```

后续实施优先新增 CRM 集成 Controller、DTO、查询投影、回写适配、鉴权及同步日志，不改变原页面接口路径、参数和响应结构。不直接把内部任意字段批量保存接口开放给 CRM。为覆盖所有变更入口仍可能需要小范围新增分支和新增数据库表；不能声称只加 Controller 就满足可靠同步。

| 源码参考 | 本版关系 |
| --- | --- |
| `plm-server/src/main/java/com/yuewei/plm/module/collaboration/MoldScheduleController.java` | 现有内部排程接口，保留原契约 |
| `plm-server/src/main/java/com/yuewei/plm/module/collaboration/MoldScheduleService.java` | 实物查询、正式保存和计划基准业务入口 |
| `plm-server/src/main/java/com/yuewei/plm/module/collaboration/CollaborationRepository.java` | 实物套、部件、编码、型号关系及状态来源 |
| `plm-server/src/main/java/com/yuewei/plm/module/collaboration/MoldScheduleTypes.java` | 三日期字段、计划/实际修订约束 |
| `plm-server/src/main/java/com/yuewei/plm/module/collaboration/MoldSchedulePolicy.java` | 待办、完成条件和业务日期下的逾期口径 |
| `plm-server/src/main/java/com/yuewei/plm/module/moldreference/PhoneModelReferenceRepository.java` | 标准型号、品牌、编码和有效状态来源 |
| `plm-server/src/main/java/com/yuewei/plm/module/moldreference/PhoneModelReferenceService.java` | 现有型号维护及关联规则，新增型号不依赖模具 |
| `plm-web/src/views/schedule/MoldScheduleView.vue` | 截图对应表格和页面有效状态 |
| `plm-web/src/utils/moldPresentation.ts` | 套级手机关系及待确认展示规则 |
| `plm-server/src/main/java/com/yuewei/plm/module/integration/crm/moldschedule/` | 旧推送实现，不视为本版接口已实现，不因编写文档启用或删除 |

## 12. 待实现后的验收方法

| 验收场景 | 预期 |
| --- | --- |
| 全部当前数据 | 独立服务账号跨全部产品范围分页读取当前实物和型号；不外传费用、附件和无关权限 |
| 记录定位 | 同款同型号多套分别返回；按 moldSetId 回写仅修改目标套，共模不重复成套 |
| 三日期回写 | 分别修改和一次修改三项，PLM 页面及 CRM 查询均显示保存值 |
| 清空与局部保存 | null 清空指定项，未提交项不变；清空不采用建议值、不标记节点完成 |
| 白名单 | 回写其他字段或状态被拒绝，整次事务无部分成功 |
| 人工修订与冲突 | PLM 修改后进入增量；CRM 持旧版本提交被拒绝，不覆盖人工结果 |
| 自动状态 | 抵墨可改变相应状态，但不越过到厂/验收；计划调整影响待办和延期、不制造实际完成 |
| 新型号 | 无申请、模具也可查到；编码 null 后分配为 0185，同一 ID 更新并保留前导零 |
| 关系准确 | 未确认适配不标为已确认，不按名称猜码，不以历史申请码填充标准空码 |
| 删除/停用/恢复 | 两类资源均有明确标记；CRM 移出当前使用，禁止停用模具回写；恢复按原 ID 更新 |
| 无历史迁移 | 首次只同步当前数据及起点后的变更，不回放接入前操作历史 |
| 稳定同步 | 首次翻页期间新增/修改/删除、提交顺序不同及同一时刻变化均可由增量补偿 |
| 超时幂等 | 同键重试/并发只产生一次保存效果，同键改内容返回冲突 |
| 游标过期 | 返回 410；两类数据完整扫描及补偿成功后才移除旧副本，失败扫描不清空 CRM |
| 原功能回归 | 原排程页面、人工修改、基准、批量保存和导入保持原契约 |

以上为验收标准，尚未开发或执行这些测试。

## 13. 联调前需提供的技术参数

业务字段、三个日期清空和人工修改、全部范围及不迁移历史已明确，不再列为未决业务项。技术联调仍需确定环境域名、服务凭证安全发放、网络连通、轮询频率和容量、游标/幂等保留期，以及源数据生命周期映射的实际字段。

建议轮询 60 秒、变更保留 30 天、幂等保留至少 7 天、分页令牌有效期 24 小时，均为本版设计默认值，不代表现有系统配置。业务删除/停用通知采用增量标记；若未来改为主动 Webhook，须另行定义重试、签名及回执，不能混用旧推送契约。
