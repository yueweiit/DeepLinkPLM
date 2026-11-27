# PLM → CRM 开模时间管控接口文档

> 最新设计见 [PLM–CRM 实物排程与手机型号接口文档 v3.0](./PLM-CRM-实物排程与手机型号接口文档-v3.0.md)。v1.0、v2.0 均已被替代。

> **已作废，禁止按本文继续开发或联调。** 2026-11-06 需求纠正：由 PLM 提供开模申请、编码及进度查询接口，CRM 调用查询，并向 PLM 回写经确认的实际到达时间。本文的“PLM 全量推送、CRM 只读”方向和数据范围不再适用。替代文档：[PLM-CRM 开模进度查询与日期回写接口文档 v2.0](./PLM-CRM-开模进度查询与日期回写接口文档-v2.0.md)。以下仅保留历史记录，所有实现状态描述均指旧方案，不代表新接口已实现。

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v1.0，PLM 推送接口最终稿 |
| 编写日期 | 2026-11-06 |
| 业务来源 | PLM「开模时间管控」页面 |
| 同步方向 | PLM 主动推送，CRM 接收并更新 |
| 数据权威来源 | PLM |
| 当前状态 | PLM 推送端已实现并完成编译/专项测试；CRM 接收端尚未提供，未进行实际联调 |

> 本文定义 PLM → CRM v1.0 报文契约。PLM 发送端已按本文实现；CRM 接收端需按本文实现并通过联调。CRM 环境地址、密钥、字段落库映射和关联 ID 来源尚待双方提供/确认。示例均为虚构数据。

## 1. 范围与同步单位

以「开模时间管控」中一套模具的进度记录为同步单位。每条记录使用稳定的 `recordId`，在 v1 中取 PLM `moldSetId` 的字符串形式。CRM 唯一键为 `(sourceSystem, entityType, recordId)`。

同一款式、产品或手机型号可能对应多套模具；同一套模具也可能适用于多个手机型号或关联多个 SKU。CRM 必须按上述唯一键更新，不得按款式名称、手机型号、实物编号或 SKU 合并记录。

本次传输包含基础信息、计划、预测、实际进度、PC/TPU 明细、负责人、排程备注、交付及延期状态。以已保存的正式数据为准，未保存的页面编辑不推送。尚未建立模具套的申请，待建立正式模具套后纳入同步。

CRM 保存该数据的同步副本并用于展示。本推送协议不包含 CRM 修改日期后回写 PLM 的能力；如需回写，应另行约定编辑接口、权限及冲突处理。

## 2. 同步流程

1. 首次接入，由 PLM 执行全量同步，将范围内现有记录逐条推送。
2. PLM 正式保存数据时，在同一数据库事务内记录待发送任务；保存成功后，后台异步推送。
3. CRM 校验身份、字段、记录 ID 和版本，在事务内保存业务数据及接收凭据。
4. CRM 提交成功后返回 HTTP 200 和业务成功回执。
5. PLM 收到有效回执后将任务标记为成功；失败按第 8 节处理。

CRM 不可用不应阻塞 PLM 正常保存。PLM 保存事务回滚时不得产生可发送的业务变更任务。数据库事务中不直接等待 CRM 网络请求。

### 2.1 触发条件

以下变化影响接口字段时，均需要推送受影响模具套的最新完整记录：

- 页面保存、批量保存、导入提交、在线表更新。
- PC/TPU 的到厂、验收、签收等正式数据变化。
- 留存计划基准、计划核对状态、暂停或恢复执行等变化。
- 款式、适用手机、负责人名称或已确认 CRM 关联关系变化。
- 预测结果、交付状态、延期状态或延期天数变化。
- 删除、作废、恢复，或正式移出同步范围。

关联资料一次修改可能影响多套模具，应分别生成这些模具套的同步任务。实施时须覆盖所有实际写入入口，不能仅在页面保存按钮处触发。

### 2.2 日期变化引起的状态更新

PLM 按 `Asia/Shanghai` 每日重新计算延期状态。延期状态、逾期节点或延期天数发生变化时，生成新版本并推送，即使当天没有人工编辑。

CRM 使用 PLM 提供的计划、预测、实际和延期结果，不自行套用另一套计算规则。状态对应的业务日期见 `status.asOfDate`。

## 3. CRM 接收接口

| 项目 | 约定 |
| --- | --- |
| 接口提供方 | CRM |
| 接口调用方 | PLM 后台同步服务 |
| 方法 | `POST` |
| 建议路径 | `/api/integrations/v1/plm/mold-schedules/events` |
| 完整地址 | 待 CRM 提供，各环境独立配置 |
| 传输协议 | HTTPS |
| 请求类型 | `application/json; charset=utf-8` |
| 响应类型 | `application/json; charset=utf-8` |
| 请求粒度 | 每次一个事件，包含一套模具的完整快照或删除标记 |
| 建议超时 | 连接 5 秒，响应 15 秒；实施时支持配置 |

### 3.1 请求头与认证

PLM 当前实现采用 HMAC-SHA256 签名认证。CRM 接收端应按下述算法验签；测试与生产分别使用不同的 Key ID 和密钥。

| 请求头 | 必填 | 说明 |
| --- | --- | --- |
| `Content-Type` | 是 | `application/json; charset=utf-8` |
| `X-PLM-Key-Id` | 是 | 密钥标识，用于选择密钥及轮换；不是密钥本身 |
| `X-PLM-Timestamp` | 是 | 当前发送时间，Unix 秒，十进制字符串 |
| `X-PLM-Event-Id` | 是 | 与正文 `eventId` 相同 |
| `X-PLM-Signature` | 是 | 下述签名结果，小写十六进制字符串 |

签名原文使用 UTF-8 编码，以换行符 LF 连接，末尾不额外添加换行：

```text
POST
/api/integrations/v1/plm/mold-schedules/events
{X-PLM-Key-Id}
{X-PLM-Timestamp}
{X-PLM-Event-Id}
{bodySha256}
```

其中 `bodySha256` 为实际 HTTP 请求体原始字节的 SHA-256 小写十六进制值；签名为 `HMAC-SHA256(secret, 签名原文)` 的小写十六进制值。密钥按 UTF-8 字符串原值作为 HMAC key 使用，不做 Base64 解码。双方不得在验签前通过重新序列化 JSON 替代原始请求体。接口不携带查询参数；若更换接口路径，双方同时调整签名配置。

CRM 应采用恒定时间比较签名，校验头部与正文事件 ID 一致，并允许时间戳与当前时间偏差不超过 300 秒。两端保持时钟同步。每次重试重新生成时间戳与签名，正文事件 ID、版本和快照保持不变。

PLM 配置的 `endpoint-url` 仅包含 HTTPS 主机（可带根路径 `/`），实际请求路径由 `path` 配置，默认值为本文路径。签名使用配置后的路径，CRM 网关不得在验签前改写该路径或请求体字节。

测试与生产密钥分别配置，支持按密钥标识轮换。密钥通过双方约定的保密渠道交付，不写入本文、业务报文或日志。

## 4. 报文结构与字段规则

### 4.1 通用约定

- JSON 字段使用 camelCase，编码使用 UTF-8。
- 所有 ID、版本值使用字符串，防止 JavaScript 大整数精度丢失。`recordVersion` 为无前导零的十进制正整数字符串，必须按整数比较，不能按字符串字典序比较。
- 业务日期为 `YYYY-MM-DD`，按 `Asia/Shanghai` 解释，不转换为 UTC 日期。
- 时间戳为带偏移的 RFC 3339 字符串，例如 `2026-11-06T14:30:00+08:00`。
- 日期未知或未填写使用 `null`，不使用空字符串、`0`、`0000-00-00` 等占位值。
- `upsert` 为完整快照。第 4 节定义的数据字段均须出现；未知的可空值传 `null`，未知或没有成员的数组传 `[]`。缺少必需字段应报错，不能隐式沿用 CRM 旧值。
- 完整快照中的 `null` 表示该同步字段当前为空，CRM 应清空旧值；`[]` 表示清空该同步集合。
- CRM 仅更新本协议约定的 PLM 同步字段，保留其自身独立维护的其他字段。
- 负责人同时提供 PLM ID 和名称；CRM 不得将 PLM 用户 ID 直接视为 CRM 用户 ID。

### 4.2 事件信封

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `schemaVersion` | string | 是 | 固定为 `1.0` |
| `eventId` | string | 是 | 全局唯一事件 ID，建议 UUID；同一次事件重试不变 |
| `eventType` | string | 是 | `moldSchedule.upsert` 或 `moldSchedule.delete` |
| `sourceSystem` | string | 是 | 固定为 `PLM`；测试与生产应使用独立接收环境 |
| `entityType` | string | 是 | 固定为 `moldSchedule` |
| `recordId` | string | 是 | 模具套 ID，v1 与 `data.moldSetId` 相同 |
| `recordVersion` | string | 是 | 该记录的同步发布版本，严格递增 |
| `occurredAt` | string | 是 | 该快照生成并记录为待发送事件的时间；重试不变 |
| `syncMode` | string | 是 | `initial` 首次全量、`change` 变更、`reconcile` 补同步 |
| `data` | object/null | 是 | `upsert` 为第 4.3 节完整对象；`delete` 为 `null` |
| `deleteReason` | string/null | 是 | `upsert` 为 `null`；`delete` 为 `deleted`、`voided` 或 `out_of_scope` |

`recordVersion` 是 PLM 独立维护的 CRM 发布版本，不等于现有 `planVersion`、主数据版本、更新时间或消息发送次数。首次发布从 `1` 开始；后续对外快照变化时递增。基准版本不变时，也可能产生新的同步版本。

同一同步版本必须始终对应同一份已保存快照。任务应保存不可变报文，不能重试时重新查询最新业务数据再套用旧版本。首次全量或补发不改变快照时可以沿用当前版本；若生成新快照，必须分配新版本。

### 4.3 完整业务对象 `data`

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `moldSetId` | string | 否 | PLM 模具套 ID，与 `recordId` 相同 |
| `itemId` | string | 否 | 所属协作主档 ID |
| `assetNumber` | string | 是 | 实物编号，由 PLM 按页面口径返回 |
| `setNo` | integer | 是 | 套次 |
| `setName` | string | 是 | 模具套名称 |
| `productLineId` | string | 是 | PLM 产品线 ID |
| `productLineName` | string | 是 | 产品线/款式展示名称，按页面来源映射 |
| `styleName` | string | 是 | 款式名称 |
| `brand` | string | 是 | 手机品牌 |
| `phoneModelCode` | string | 是 | PLM 手机型号编码，前导零必须保留 |
| `compatibleModels` | string | 是 | 本套适用手机展示文字，保持页面口径 |
| `compatiblePhoneModelIds` | string[] | 否 | 已确认适用于本套的 PLM 标准手机型号 ID；没有已确认关联时为 `[]` |
| `crmLinks` | object | 否 | 第 4.4 节 CRM 身份关联 |
| `plan` | object | 否 | 第 4.5 节计划安排 |
| `forecast` | object | 否 | 第 4.6 节预测结果 |
| `actual` | object | 否 | 第 4.7 节实际进度 |
| `parts` | object[] | 否 | 第 4.8 节部件明细，固定包含 PC、TPU 两项 |
| `owners` | object | 否 | 第 4.9 节负责人 |
| `status` | object | 否 | 第 4.10 节状态 |
| `scheduleNotes` | string | 是 | 排程备注 |
| `updatedAt` | string | 否 | 本条对外业务快照的最近更新时间，涵盖关联资料和状态计算变化；仅用于展示，版本判断使用 `recordVersion` |

### 4.4 CRM 关联 `crmLinks`

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `mappingStatus` | string | 否 | 协议状态：`unmapped` 未建立明确关联、`partial` 部分建立、`mapped` 已完成适用范围内映射 |
| `seriesIds` | string[] | 否 | 已确认的 CRM 产品系列 ID |
| `phoneModelIds` | string[] | 否 | 已确认的 CRM 手机型号 ID |
| `skuIds` | string[] | 否 | 已确认关联的 CRM SKU ID，可一对多 |

只传已有明确来源、且确认适用于本套的关联 ID，不按名称猜测，不因产品相同就关联该产品全部 SKU。`[]` 不代表停用或删除 CRM 商品。即使关联缺失，CRM 仍按 PLM 唯一键保存进度记录，并显示关联待完善。

这些数组完整表达当前已确认的关联；取消关联后，下一版本应移除旧 ID。具体 CRM 对象名称及已有映射来源需双方核定。

### 4.5 计划安排 `plan`

除天数及版本外，本表字段类型均为可空日期字符串。

| 字段 | 类型 | 可空 | 页面含义 |
| --- | --- | --- | --- |
| `planStartDate` | string | 是 | 计划开模 |
| `manufacturingDays` | integer | 是 | 制作天数 |
| `holidayDays` | integer | 是 | 假期天数 |
| `extraDays` | integer | 是 | 额外天数 |
| `plannedMoldDeliveryDate` | string | 是 | 计划模具交付 |
| `plannedFactoryArrivalDate` | string | 是 | 计划到墨工厂 |
| `plannedAcceptanceDate` | string | 是 | 计划验收 |
| `plannedProductionDate` | string | 是 | 计划试模量产 |
| `planVersion` | string | 否 | 已留存计划基准版本；未留存时为 `0` |
| `baselineNeedsReview` | boolean | 否 | 计划变化是否需要核对基准 |

接口不另行制定制作周期、假期或日期先后校验规则，按 PLM 已有业务规则输出。当前计划日期与留存基准可能不同，本版只传当前值及基准版本/核对标记，不传历史基准完整内容。

### 4.6 当前预测 `forecast`

以下字段均为可空日期字符串，传 PLM 当前业务计算/保存结果，不能以计划日期静默替代缺失的预测日期。

| 字段 | 页面含义 |
| --- | --- |
| `forecastDeliveryDate` | 预测模具交付 |
| `forecastFactoryDate` | 预测到厂 |
| `forecastAcceptanceDate` | 预测验收 |
| `forecastProductionDate` | 预测量产 |

### 4.7 实际进度 `actual`

以下字段均为可空日期字符串。实际日期只表示已登记的业务事实，不能自动填入计划或预测日期。

| 字段 | 页面含义 |
| --- | --- |
| `openedDate` | 开模日期 |
| `actualSupplierTrialDate` | 中国实际试模 |
| `actualShipDate` | 实际发模 |
| `actualMexicoArrivalDate` | 实际抵墨 |
| `actualPortArrivalDate` | 实际到港 |
| `actualTrialDate` | 墨西哥试模 |
| `actualProductionDate` | 实际量产 |
| `marketReadyDate` | 上市确认日期 |

实际抵墨、实际到港、PC/TPU 到厂和验收是不同节点，CRM 分别保存与展示，不能互相代填。

### 4.8 部件明细 `parts[]`

数组固定包含 `partType=PC` 和 `partType=TPU` 两项，各一项；即使尚无部件资料，也保留对象并将可空字段置 `null`。

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `partType` | string | 否 | `PC` 或 `TPU` |
| `statusCode` | string | 是 | PLM 部件业务状态原代码 |
| `statusName` | string | 是 | 对应状态中文名称 |
| `physicalStatusCode` | string | 是 | PLM 实物状态原代码 |
| `physicalStatusName` | string | 是 | 对应实物状态中文名称 |
| `signedDate` | string | 是 | 签收日期 |
| `factoryArrivalDate` | string | 是 | 实际到厂日期 |
| `acceptanceStatusCode` | string | 是 | PLM 验收状态原代码 |
| `acceptanceStatusName` | string | 是 | 对应验收状态中文名称 |
| `acceptanceDate` | string | 是 | 验收日期 |
| `acceptanceIssues` | string | 是 | 验收问题 |

PC/TPU 签收日期来源于模具套对应字段；到厂和验收信息来源于各自部件资料。数据库与展示代码大小写差异由 PLM 映射处理。

### 4.9 负责人 `owners`

包含 `mold`（模具）、`logistics`（物流）、`acceptance`（验收）、`production`（量产）四个对象，每个对象均包含：

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `userId` | string | 是 | PLM 用户 ID |
| `displayName` | string | 是 | PLM 显示姓名 |

未分配时两个字段均为 `null`。不传手机号、账号密码等本次展示不需要的信息。

### 4.10 状态 `status`

| 字段 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `scheduleStatusCode` | string | 是 | PLM 交付状态原代码 |
| `scheduleStatusName` | string | 是 | 交付状态中文名称 |
| `schedulePaused` | boolean | 否 | 是否暂停执行；暂停不等于删除 |
| `marketEligible` | boolean | 否 | 按 PLM 口径是否满足上市验收条件 |
| `overdue` | boolean | 否 | 是否存在逾期节点 |
| `overdueDays` | integer | 是 | 存在逾期时为节点延期天数最大值；无逾期时为 `0`，无法计算时为 `null` |
| `overdueTasks` | object[] | 否 | 逾期节点列表；无逾期时为 `[]` |
| `asOfDate` | string | 否 | 状态计算的业务日期，例如 `2026-11-06` |

`overdueTasks[]` 每项包含 `taskKey`（string，节点代码）、`title`（string，中文名称）、`dueDate`（日期字符串，可空）、`overdueDays`（integer，可空）。延期含义以页面现有业务计算口径为准；具体节点代码清单见第 12 节待核定项。

状态代码沿用 PLM，中文名供 CRM 直接展示。CRM 应保留不认识的代码并使用名称展示，避免新增状态导致整条接收失败。完整业务状态字典需在联调前补齐，本文不凭数据库样本推断全部枚举。

## 5. 请求示例

### 5.1 新增或更新

以下为虚构示例；`upsert` 同时用于新增和更新。

```json
{
  "schemaVersion": "1.0",
  "eventId": "4a5cbcf4-3620-49ed-8d18-5c5a8c313001",
  "eventType": "moldSchedule.upsert",
  "sourceSystem": "PLM",
  "entityType": "moldSchedule",
  "recordId": "900001",
  "recordVersion": "12",
  "occurredAt": "2026-11-06T14:30:00+08:00",
  "syncMode": "change",
  "deleteReason": null,
  "data": {
    "moldSetId": "900001",
    "itemId": "800001",
    "assetNumber": "DEMO-MS-000001",
    "setNo": 1,
    "setName": "示例第一套",
    "productLineId": "700001",
    "productLineName": "示例产品线",
    "styleName": "示例款式",
    "brand": "示例品牌",
    "phoneModelCode": "0001",
    "compatibleModels": "示例手机 A / 示例手机 B",
    "compatiblePhoneModelIds": ["600001", "600002"],
    "crmLinks": {
      "mappingStatus": "unmapped",
      "seriesIds": [],
      "phoneModelIds": [],
      "skuIds": []
    },
    "plan": {
      "planStartDate": "2026-11-01",
      "manufacturingDays": 10,
      "holidayDays": 0,
      "extraDays": 2,
      "plannedMoldDeliveryDate": "2026-11-13",
      "plannedFactoryArrivalDate": "2026-11-20",
      "plannedAcceptanceDate": "2026-11-21",
      "plannedProductionDate": "2026-11-24",
      "planVersion": "2",
      "baselineNeedsReview": false
    },
    "forecast": {
      "forecastDeliveryDate": "2026-11-15",
      "forecastFactoryDate": "2026-11-22",
      "forecastAcceptanceDate": "2026-11-23",
      "forecastProductionDate": "2026-11-26"
    },
    "actual": {
      "openedDate": "2026-11-01",
      "actualSupplierTrialDate": null,
      "actualShipDate": null,
      "actualMexicoArrivalDate": null,
      "actualPortArrivalDate": null,
      "actualTrialDate": null,
      "actualProductionDate": null,
      "marketReadyDate": null
    },
    "parts": [
      {
        "partType": "PC",
        "statusCode": "pending",
        "statusName": "待确认",
        "physicalStatusCode": null,
        "physicalStatusName": null,
        "signedDate": null,
        "factoryArrivalDate": null,
        "acceptanceStatusCode": "pending",
        "acceptanceStatusName": "待验收",
        "acceptanceDate": null,
        "acceptanceIssues": null
      },
      {
        "partType": "TPU",
        "statusCode": "pending",
        "statusName": "待确认",
        "physicalStatusCode": null,
        "physicalStatusName": null,
        "signedDate": null,
        "factoryArrivalDate": null,
        "acceptanceStatusCode": "pending",
        "acceptanceStatusName": "待验收",
        "acceptanceDate": null,
        "acceptanceIssues": null
      }
    ],
    "owners": {
      "mold": {"userId": "500001", "displayName": "示例模具负责人"},
      "logistics": {"userId": null, "displayName": null},
      "acceptance": {"userId": null, "displayName": null},
      "production": {"userId": null, "displayName": null}
    },
    "status": {
      "scheduleStatusCode": null,
      "scheduleStatusName": null,
      "schedulePaused": false,
      "marketEligible": false,
      "overdue": false,
      "overdueDays": 0,
      "overdueTasks": [],
      "asOfDate": "2026-11-06"
    },
    "scheduleNotes": "示例：交付预测已调整，计划基准保留。",
    "updatedAt": "2026-11-06T14:30:00+08:00"
  }
}
```

### 5.2 删除、作废或移出范围

```json
{
  "schemaVersion": "1.0",
  "eventId": "4a5cbcf4-3620-49ed-8d18-5c5a8c313002",
  "eventType": "moldSchedule.delete",
  "sourceSystem": "PLM",
  "entityType": "moldSchedule",
  "recordId": "900001",
  "recordVersion": "13",
  "occurredAt": "2026-11-06T15:00:00+08:00",
  "syncMode": "change",
  "deleteReason": "deleted",
  "data": null
}
```

CRM 隐藏或软删除对应进度副本，保留接收版本和删除标记，不级联删除 CRM 产品、型号、SKU 或其他业务数据。尚未收到新增就先收到删除时，也要记录删除版本。恢复时 PLM 发送更高版本的完整 `upsert`，CRM 恢复展示。

## 6. 成功回执与版本处理

### 6.1 HTTP 200 回执

```json
{
  "code": "SUCCESS",
  "message": "已保存",
  "eventId": "4a5cbcf4-3620-49ed-8d18-5c5a8c313001",
  "recordId": "900001",
  "requestVersion": "12",
  "currentVersion": "12",
  "result": "applied",
  "receivedAt": "2026-11-06T14:30:01+08:00"
}
```

`code`、`eventId`、`recordId`、`requestVersion`、`currentVersion`、`result`、`receivedAt` 均必填；`message` 可选，仅供说明。`result` 的取值：

| 条件 | CRM 行为 | `result` |
| --- | --- | --- |
| 不存在该记录，或收到版本大于已保存版本 | 保存完整快照/删除标记及版本 | `applied` |
| 已成功处理相同事件，或相同版本的相同业务快照 | 不重复写业务数据 | `duplicate` |
| 收到版本小于已保存版本 | 保留当前数据，不回退 | `ignored_stale` |

三种情况均返回 HTTP 200、`code=SUCCESS`。对于 `ignored_stale`，`currentVersion` 返回 CRM 当前较高版本；对于其余两种，返回已保存且不低于请求的版本。

相同事件 ID 对应不同正文，或相同记录版本对应不同业务快照，应返回 HTTP 409，记录冲突并人工排查。业务快照比较范围为 `eventType`、`deleteReason`、`data`；忽略 JSON 对象键顺序，但必须保证相同版本的业务内容一致。不同同步模式或事件 ID 不构成业务内容变化。

### 6.2 CRM 事务与并发要求

- 唯一键约束、版本比较、业务数据更新、接收凭据和删除标记必须具备事务一致性。
- 同一记录并发接收时，使用行锁或原子条件更新等方式，保证较低版本无法覆盖较高版本。
- 版本不要求连续，例如当前 `10` 可直接接受 `12`，因为每次发送完整快照。
- HTTP 200 只能在 CRM 数据持久保存成功后返回；本版不把单纯入内存队列、HTTP 202 或 HTTP 204 视为同步完成。

PLM 仅在 HTTP 200、`code=SUCCESS`、事件/记录/请求版本匹配、`result` 合法且 `currentVersion >= requestVersion` 时确认成功。回执缺失、内容不合法或无法解析均不得标记为成功。

PLM 实际校验 `code`、`eventId`、`recordId`、`requestVersion`、`currentVersion`、`result` 和可解析的 `receivedAt`；`message` 仅用于说明，不参与成功判定。`currentVersion` 必须是正整数字符串，并按整数比较。

CRM 保留每条记录的最高版本和删除标记，不随短期日志清理而丢失。事件接收明细建议保留至少 90 天；即使明细过期，最高版本仍能阻止旧数据覆盖。

## 7. 错误回执

```json
{
  "code": "INVALID_PAYLOAD",
  "message": "data.actual.actualShipDate 日期格式错误",
  "eventId": "4a5cbcf4-3620-49ed-8d18-5c5a8c313001",
  "recordId": "900001",
  "requestVersion": "12",
  "retryable": false,
  "errors": [
    {"field": "data.actual.actualShipDate", "reason": "应为 YYYY-MM-DD 或 null"}
  ]
}
```

错误回执字段均应返回，无法安全取得的 `eventId`、`recordId`、`requestVersion` 为 `null`，没有字段错误时 `errors=[]`。不得在回执或日志中泄露密钥、数据库连接信息或内部堆栈。

| HTTP 状态 | 业务代码示例 | PLM 处理 |
| --- | --- | --- |
| 400–499（429 除外） | `INVALID_PAYLOAD`、`INVALID_SIGNATURE`、`FORBIDDEN` 等 | PLM 不自动重试，置为失败；修复后由管理员补发 |
| 429 | `RATE_LIMITED` | 自动重试；PLM 使用配置退避间隔与合法 `Retry-After` 中较长者 |
| 500–599 | `INTERNAL_ERROR`、`SERVICE_UNAVAILABLE` 等 | 自动重试，达到上限后置为失败 |
| 网络异常、连接/响应超时 | 无有效回执 | 自动重试；CRM 可能已保存，依靠幂等接收 |
| HTTP 200 但回执缺失、格式错误或与请求不匹配 | PLM 回执校验失败记录 | 自动重试，达到上限后置为失败 |
| 其他非 200 状态 | 由 CRM/网关返回 | 不标记成功；按非可重试失败处理 |

PLM 当前仅根据 HTTP 状态和 ACK 内容判定重试，不读取错误 JSON 中的 `retryable` 字段。CRM 应确保错误响应使用非 200 HTTP 状态；HTTP 200 仅用于已持久化或幂等确认成功的回执。CRM 配置未就绪时 PLM 暂停投递，事件留在队列中；配置修复后后台继续处理。

## 8. 重试、补发与运行记录

PLM 首次发送失败后按以下间隔自动重试，最多 8 次重试（即最多 9 次投递尝试）：

```text
10 秒 → 30 秒 → 1 分钟 → 5 分钟 → 15 分钟 → 30 分钟 → 1 小时 → 3 小时
```

每次退避间隔增加 0～20% 随机抖动。HTTP 429 带合法 `Retry-After` 时，实际等待时间取退避间隔与 `Retry-After` 的较大值。非 429 的 4xx、非 200 非可重试状态及配置无效不会自动重试；修复后由管理员补发。

达到上限后保留失败任务并置为 `failed`。PLM 当前提供管理员失败任务补发入口；未配置内建告警通知。普通重试重用原事件及不可变正文；业务发生新变化时生成更高版本，不修改旧事件正文。

PLM Outbox 保留事件 ID、记录 ID、同步版本、触发来源、任务状态、尝试次数、下次重试时间、最近 HTTP 状态、错误摘要、生成/成功时间及用于重试的不可变报文；当前不记录独立的同步批次 ID。普通运行日志不打印完整业务报文。

PLM 任务状态为 `pending`、`sending`、`retrying`、`succeeded`、`failed`。后台默认每 5 秒检查一次待发送事件；进程异常退出后可恢复超时的 `sending` 任务。投递采用“至少一次”方式，重复消息属于正常情况，由 CRM 幂等处理。

## 9. 首次全量与补同步

1. PLM 管理员调用全量入口后，PLM 按模具套 ID 分页扫描当前有效记录，使用 `syncMode=initial` 生成完整快照事件。
2. 每条记录的快照、发布版本和 Outbox 事件由 PLM 一致写入；变更触发的事件可与全量事件交错到达，CRM 按版本保存最高版本。
3. PLM 按单条事件投递，扫描/入队完成不等于 CRM 已接收完成；应以 Outbox 成功状态和 CRM 记录核对为准。
4. PLM 通过快照摘要去重：若记录已有同步状态且业务快照未变化，全量入口会跳过该记录，不强制重发相同快照。
5. 因此该入口适用于首次初始化；若 CRM 数据被清空或需强制修复未变化记录，当前版本没有强制重放能力，须先与 PLM 运维确认补同步方案，不可将再次调用该入口视为全量重建。

全量不清空 CRM 后重建，CRM 也不能因某条记录未出现在某次扫描中就自行删除。删除依靠显式删除事件；CRM 如已有历史残留，应先核对记录清单，再由 PLM 发出有版本的删除标记。

同一 PLM 数据源恢复备份或迁移时，必须保留同步版本和删除历史，不得归零后直接向原 CRM 目标继续推送。若丢失同步状态，应先核对 CRM 已保存版本并完成受控恢复，再恢复投递。

## 10. 与当前 PLM 的对照说明

以下用于说明 PLM 当前的数据组装来源，不是允许 CRM 直接访问数据库的约定。

| 业务内容 | 当前可核对来源 |
| --- | --- |
| 页面 | `/mold-schedules`，开模时间管控 |
| 页面现有排程 API | `/api/v1/collaboration-plans/schedules` 及其 options、详情、batch-save、plans、history 等能力 |
| 模具套及多数日期 | `plm_collaboration_mold_set` |
| 款式、品牌等协作主档 | `plm_collaboration_item` |
| PC/TPU 到厂及验收 | `plm_collaboration_mold_part` |
| 已留存计划基准 | `plm_mold_schedule_plan` |
| 标准手机与本套适配关系 | 复用 PLM 已确认的手机型号/模具套关联 |
| 交付、预测、延期、显示名称 | 复用 `MoldSchedulePolicy`、`MoldScheduleTypes` 及页面数据组装结果 |

当前实现新增独立 CRM 发布版本和 Outbox，不复用计划基准版本。PLM 发送端已在正式保存、计划基准、模具套和相关产品/负责人资料变化等服务入口接入队列；未保存草稿不推送。现有页面查询/保存 API 不是 CRM 接收 API。

## 11. 联调验收清单

| 场景 | 预期 |
| --- | --- |
| 首次全量 | 首次初始化时范围内每套有效模具均入队；CRM 最终记录与 PLM 已保存结果一致 |
| 同款多套、共用手机、多 SKU | 分别按模具套唯一键保存，关联不造成重复/覆盖 |
| 单次保存和批量/导入/在线表更新 | 正式保存后均触发；未保存草稿不发送 |
| 日期清空及关联移除 | `null`、`[]` 能清除 CRM 同步字段的旧值 |
| 计划/预测/实际及 PC/TPU 区分 | 各字段独立保存，不互相代填 |
| 大整数 ID、前导零型号编码 | 无精度丢失、无前导零丢失 |
| 同一事件重复发送 | 返回 `duplicate`，不重复新增或触发重复业务动作 |
| 新版本先到、旧版本后到 | 旧版本返回 `ignored_stale`，数据不回退 |
| 同一版本不同内容 | 返回 409，保留当前数据并记录冲突 |
| CRM 保存后回执丢失 | PLM 重试后获得成功回执，CRM 记录不重复 |
| CRM 事务失败、网络断开、429、5xx | 不标记成功，按约定重试，恢复后同步完成 |
| 签名错误、过期时间戳 | 拒绝接收；旧事件重新签名后可合法重试 |
| 删除先到、旧更新后到、后续恢复 | 删除版本可防止旧数据复活，更高版本可恢复 |
| 全量扫描时并发修改 | 最终保存最高版本，无漏变更 |
| 跨日延期、延期天数变化、负责人改名 | 每日复核发现快照业务字段变化时推送新版本；单纯 `asOfDate`/`updatedAt` 变化不单独增版本 |
| 关联缺失、新状态代码 | 进度仍可保存，保留原代码/名称及待关联状态 |
| 服务重启和失败补发 | 未完成任务可恢复，版本不回退 |
| 数据源备份恢复 | 同步版本/删除历史得到恢复或核对，不发生版本归零覆盖 |

## 12. CRM 接入前待确认事项

| 事项 | 待确认内容 | 负责方 |
| --- | --- | --- |
| 接收地址 | 测试/生产域名、路径、网络可达性 | CRM |
| 认证配置 | 接收端按本文 HMAC-SHA256 验签；测试/生产 Key ID、密钥交付与轮换 | 双方 |
| 字段映射 | CRM 页面/存储字段，产品线展示名称、本套适用手机的准确映射 | 双方 |
| 发布版本 | CRM 以整数规则保存最高 `recordVersion` 和删除标记，重复/旧版本按第 6 节幂等处理 | CRM |
| 同步范围 | 正式记录纳入规则、历史记录范围、移出范围与作废的业务判定 | 双方 |
| 首次同步 | 执行时间、范围、CRM 已有数据及历史残留处理；注意第 9 节去重限制 | 双方 |
| 性能参数 | 请求体上限、CRM 限流、发送容量；PLM 默认连接超时 5 秒、响应超时 15 秒、轮询间隔 5 秒 | 双方 |
| 状态字典 | CRM 保留未知 PLM 状态代码并可显示名称；枚举清单联调时核对 | PLM/CRM |
| 关联标识 | CRM 系列/型号/SKU 对象 ID 的准确来源；未确认前 PLM 固定发送 `mappingStatus=unmapped` 和空数组 | 双方 |
| 日常运维 | 失败任务查看、管理员补发、日志保留和告警接收；PLM 当前不含主动告警通知 | 双方 |

除上述接入配置和业务映射外，报文信封、签名、版本、删除、成功回执和重试语义构成本版 v1.0 契约。任何字段/签名/回执变更须双方版本化确认。

## 13. PLM 发送端配置与运维接口

本节是 PLM 内部部署/运维信息，不属于 CRM 需要实现的接收 API。同步默认关闭，启用前须先完成 CRM 联调并安全注入凭证。

| 环境变量 | 说明 | 默认值 |
| --- | --- | --- |
| `PLM_CRM_MOLD_SCHEDULE_ENABLED` | 是否启用发送任务 | `false` |
| `PLM_CRM_MOLD_SCHEDULE_ENDPOINT_URL` | CRM HTTPS 主机地址，不含接收路径 | 空 |
| `PLM_CRM_MOLD_SCHEDULE_PATH` | CRM 接收路径，参与 HMAC 签名 | `/api/integrations/v1/plm/mold-schedules/events` |
| `PLM_CRM_MOLD_SCHEDULE_KEY_ID` | CRM 分配的 Key ID | 空 |
| `PLM_CRM_MOLD_SCHEDULE_SECRET` | 双方安全交付的 HMAC 密钥 | 空 |
| `PLM_CRM_MOLD_SCHEDULE_CONNECT_TIMEOUT_MS` | 连接超时毫秒数 | `5000` |
| `PLM_CRM_MOLD_SCHEDULE_READ_TIMEOUT_MS` | 响应超时毫秒数 | `15000` |
| `PLM_CRM_MOLD_SCHEDULE_POLL_INTERVAL_MS` | Outbox 检查间隔毫秒数 | `5000` |
| `PLM_CRM_MOLD_SCHEDULE_RECONCILE_CRON` | 每日状态复核 Cron | `0 10 0 * * *` |

以下 PLM 管理接口要求 PLM 管理员权限，不应开放给 CRM 服务账号：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/api/v1/integrations/crm/mold-schedules/status` | 查看开关、配置就绪状态、各状态数量和最近事件 |
| `POST` | `/api/v1/integrations/crm/mold-schedules/full-sync` | 对当前有效模具套扫描并将需要的快照入队；遵循第 9 节摘要去重行为 |
| `POST` | `/api/v1/integrations/crm/mold-schedules/retry-failed` | 补发全部失败事件，复用原始不可变报文 |
| `POST` | `/api/v1/integrations/crm/mold-schedules/{moldSetId}/retry-failed` | 补发指定模具套的失败事件 |
