# PLM–CRM 开模进度查询与日期回写接口文档

> **已被 v3.0 替代，不再作为开发或联调依据。** 2026-11-09 根据页面截图及用户确认：查询单位改为实物模具套；CRM 仅回写实际抵墨、计划验收、计划试模量产三个日期，允许清空，PLM 也可人工修改；新增独立手机型号查询。参见 [v3.0 接口文档](./PLM-CRM-实物排程与手机型号接口文档-v3.0.md)。下文仅保留历史设计记录。

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v2.0，需求纠正后的接口设计稿，待业务字段确认 |
| 编写日期 | 2026-11-06 |
| 替代文档 | `PLM-CRM-开模时间管控最终接口文档-v1.0.md`，旧文已作废 |
| 业务入口 | PLM「开模时间管控」中的「开模时间申请」及其关联模具进度 |
| 提供方 / 调用方 | PLM 提供接口，CRM 调用 |
| 数据方向 | CRM 查询 PLM；CRM 将指定实际到达日期回写 PLM |
| 实现状态 | 本文为拟定契约；下列新接口、版本控制、入站鉴权及增量游标均尚未按本契约实现 |

本文纠正旧文的接口方向、同步单位和写入权限。本次仅重订文档，不修改业务代码，不启用旧推送，不执行数据库迁移或部署。示例 ID、编码、日期及版本均为虚构数据。

## 1. 业务范围与边界

CRM 需要获得开模申请中约定的字段及编码，按型号查看申请和后续进度，并将自己掌握的实际到达时间写回 PLM。PLM 内部完成节点或更新进度后，CRM 通过增量查询刷新展示。

本版优先提供四项能力：申请进度列表、按 ID 查询详情、实际到达日期局部回写、增量变更查询。产品、型号和 SKU 身份信息随进度记录返回，首期不单独建设一套主数据查询接口；确有独立检索需求时再新增。

需求中的图片用于说明所需接口能力，尚不足以确认业务列清单。下文的业务字段是结合现有源码提出的候选集，不能视为用户已经确认的全部传输列。尤其不能将所有“实际日期”开放给 CRM 编辑。

| 数据 | 权威来源及本版权限 |
| --- | --- |
| 申请单号、产品、型号、编码、关联关系 | PLM；CRM 只读 |
| 申请状态、建档状态、执行状态、节点进度 | PLM；CRM 只读，不接收 CRM 自行计算的完成状态 |
| 计划日期、预测日期、延期结果 | PLM；CRM 只读，实际提供哪些列待确认 |
| 指定实际到达日期 | CRM 提供；PLM 校验、保存、留痕后返回正式结果 |
| 实际到达以外的实际日期、验收和量产结果 | 本版不授予 CRM 写权限 |

查询只返回已保存数据。CRM 提交成功后，PLM 页面和后续查询使用同一份正式数据。回写保留现有业务校验，不直接修改数据库绕过业务服务。

## 2. 记录身份及关联模型

### 2.1 查询以申请为单位

建议每条查询记录代表一张开模申请：

- `sourceSystem = "PLM"`，`entityType = "MOLD_APPLICATION"`。
- `recordId` 为 PLM `applicationId` 的字符串形式，建档前后保持不变。
- CRM 使用 `(sourceSystem, entityType, recordId)` 作为申请进度记录唯一键。此键与旧文的模具套记录键不同，不能沿用旧数据直接覆盖。
- `moldSetId` 为申请关联的实物模具套 ID，未建档时允许为 `null`。型号编码、产品编码、完整模具编码均不能替代申请 ID 或实物套 ID。

建议首期可见范围为：已正式提交、非草稿、未删除，且位于 CRM 服务账号授权范围内的申请；包括尚未建档、建档受阻及已有结果的申请。是否包含拒绝、终止、历史补链申请，以及非现有页面来源的申请，须在上线前确认。不能为了方便读取进度而仅返回已建档申请。

### 2.2 申请进度与实物进度分开表达

现有源码的申请列表与模具套详情不是同一个资源。一张申请的审批状态，不等于模具已开始生产；申请已获同意，也不等于建档成功。

多个申请可能关联同一套实物模具。PC、TPU 是套内不同部件，不拆成两条申请，也不能因为共模适配多个型号而复制实物套。

本版日期回写路径保留申请 ID，同时要求提交明确的 `moldSetId`。PLM 在同一事务中检查申请关联、版本、服务账号的实物写权限与状态后，才更新对应模具套或部件。共享该模具套的其他申请会读到相同实际日期，并应进入增量变更范围。CRM 页面应提示该共享影响。

未建档或无可写实物权限的申请仍可查询，日期回写返回冲突或禁止访问。若申请仅用于历史关联，不能因此自动获得实物执行权限。

## 3. 基础约定与鉴权

| 项目 | 约定 |
| --- | --- |
| 基础路径 | `/api/integrations/v2/crm`，拟新增独立命名空间 |
| 传输与编码 | HTTPS，UTF-8；响应为 `application/json` |
| ID 与业务编码 | JSON 字符串，避免大整数精度丢失，并保留编码前导零 |
| 业务日期 | `YYYY-MM-DD`，不含时刻，不做跨时区换日 |
| 时间戳 | ISO 8601，带时区，例如 `2026-11-06T14:30:00+08:00` |
| 状态计算时区 | `Asia/Shanghai` |
| 空值 | `null` 表示该已定义字段无值，数组无关联时返回 `[]` |
| 跟踪标识 | 每个响应返回 `X-Request-Id`；错误体同时返回 `requestId` |

拟采用独立 CRM 服务凭证：`Authorization: Bearer <crm-service-token>`。由 PLM 管理员通过部署配置安全发放并支持轮换，不在本文新增登录或令牌签发接口。凭证映射到服务主体、数据范围以及独立的读/写权限；普通用户会话和旧版出站 HMAC 不视为新接口的鉴权实现。生产凭证不得出现在文档、URL 或日志中。

建议权限为 `crm:mold-progress:read` 和 `crm:arrival-date:write`。实际到达的可写字段还须逐项配置白名单。读到申请不代表能够修改其关联实物；请求体中的 CRM 操作人仅用于辅助审计，不能替代服务身份认证。

## 4. 接口总览

| 方法 | 路径（相对基础路径） | 用途 |
| --- | --- | --- |
| GET | `/opening-applications` | 分页查询申请、编码和进度 |
| GET | `/opening-applications/{recordId}` | 查询当前详情、版本及允许回写字段 |
| PATCH | `/opening-applications/{recordId}/arrival-dates` | 向明确关联的实物局部回写日期 |
| GET | `/opening-application-changes` | 获取新增、修改、删除或移出范围的变更标识 |

以上路径均为拟新增接口，不替换现有页面接口，不直接将内部 `/batch-save` 开放给 CRM。首期不要求 CRM 提供接收推送接口。变更通知如后续需要，可单独扩展，通知只提示重新查询。

## 5. 申请、编码及进度数据结构

### 5.1 身份与业务字段候选集

| 字段 | 类型 | 含义及来源 |
| --- | --- | --- |
| `sourceSystem` / `entityType` / `recordId` | string | 第 2 节定义的记录身份，固定返回 |
| `applicationId` | string | 申请 ID，与本版 `recordId` 相同 |
| `approvalNo` | string/null | PLM 申请/审批单号，只作展示和检索 |
| `recordVersion` | string | 对外聚合版本，详见第 8 节，不能用时间戳代替 |
| `updatedAt` | timestamp | 接口投影最近变更时间；关联资料变化也应计入，不只取申请更新时间 |
| `product` | object/null | `productId`、`productCode`、`productName`；使用已确认的产品主数据关系 |
| `phoneModel` | object/null | `phoneModelId`、`brandName`、`modelName`、`phoneModelCode`；优先标准机型主数据 |
| `historicalPhoneModelCode` | string/null | 历史申请原始手机码，独立返回，不覆盖标准机型码 |
| `applicationMoldCodes` | string[] | 申请中原始/预留的完整模具编码；不宣称已经形成实物 |
| `moldSetId` / `itemId` | string/null | 已关联实物套及主档 ID |
| `moldParts` | array | 已确认实物部件的 `partType`、`materialCode`、`moldCode`；未建立实物时为空 |
| `skuCodes` | string[] | 与本记录有明确关联的 SKU；无确认关系时为空 |
| `businessStatus` | string/null | 申请业务状态，复用 PLM 状态值及对应显示文案 |
| `executionStatus` | string/null | 申请执行状态，与审批业务状态分开 |
| `provisioningStatus` | string/null | 建档状态，允许尚未建档或建档受阻 |
| `progress` | object | `scheduleStatus`、`scheduleStatusText`、`asOfDate`；无实物进度时状态为 null |
| `expectedDeliveryDate` | date/null | 申请表中的期望交付日期，不等同于模具排程计划日期 |
| `dates` | object | 选定计划/实际日期，候选见下表 |
| `arrivalWritableFields` | string[] | 当前账号、当前记录实际允许写入的日期键；没有权限时为空 |

申请人、当前处理人、备注、附件和内部异常原文不默认外传。确实需要时逐列补充用途及授权，不直接透传完整申请 `form` 或数据库行。

### 5.2 日期候选字段及权限

| `dates` 内字段 | 现有业务含义 | CRM 权限建议 |
| --- | --- | --- |
| `openedDate` | 实际开模日期 | 只读 |
| `plannedMoldDeliveryDate` | 计划交模日期 | 只读 |
| `plannedFactoryArrivalDate` | 计划到厂日期 | 只读 |
| `actualShipDate` | 实际发模日期 | 只读 |
| `actualMexicoArrivalDate` | 实际抵达墨西哥日期 | 候选回写项，待确认 |
| `actualPortArrivalDate` | 实际到港日期 | 候选回写项，待确认 |
| `pcFactoryArrivalDate` | PC 实际到厂日期 | 候选回写项，待确认 |
| `tpuFactoryArrivalDate` | TPU 实际到厂日期 | 候选回写项，待确认 |

本表是建议范围，最终仍按业务列清单裁剪。签收日期、验收日期、实际到厂日期是不同节点；预测日期也不等于计划或实际日期。不能新增一个含义不明的 `actualArrivalDate` 并自动映射到上述全部字段。回写白名单在业务确认前为空；若最终仅有一个到达节点由 CRM 提供，就只开放该节点。

详情可返回 `arrivalWritableFields` 供 CRM 控制输入框，但服务端每次保存仍要重新检查。状态码的完整枚举及中文文案应从现有业务定义整理并随字段清单一并冻结，不能直接将页面文案作为可写状态码。

### 5.3 编码约定

遵守仓库 `coding-rules.md` 和 `coding-rules-system-implementation.md`：

- 产品编码例如 `MBA`，内部产品特定码为 `BA`；完整模具编码 `MBA101199 = MBA + 10 + 1199`，共九位。材质码 TPU=`10`、PC=`20`。
- 四位手机型号码是字符串，例如 `"0185"`，必须保留前导零。名称用于展示和搜索，不能按名称猜码、补零或重写原始编码。
- 如果标准机型存在但标准编码为空，`phoneModel.phoneModelCode` 返回 `null`，不能拿历史申请码填充。原码仅进入 `historicalPhoneModelCode`。
- 历史五位前缀如 `MBA10`、`LLT10` 不是完整模具身份，不能混进完整模具编码数组；有外传需要时另设历史原值字段。
- SKU 遵守 `N + 两位产品特定码 + 四位工序码 + 四位机型码 + 两位颜色码`。最终工序以产品配置为准，不能将所有 N 开头编码都归为成品。
- SKU 和共模只取已有明确关联，不能把整个产品下的 SKU 自动关联给某张申请。PLM ID 与 CRM ID 分开维护，禁止按名称覆盖关联。

### 5.4 详情响应示例

`GET /api/integrations/v2/crm/opening-applications/1024`

响应 `200 OK`，头部 `ETag: "rv-example-17"`。下面仅为候选结构示例；其中假定业务已确认允许 CRM 回写“实际到港日期”，不代表当前已开放。

```json
{
  "data": {
    "sourceSystem": "PLM",
    "entityType": "MOLD_APPLICATION",
    "recordId": "1024",
    "applicationId": "1024",
    "approvalNo": "AP-20261106-001",
    "recordVersion": "rv-example-17",
    "updatedAt": "2026-11-06T14:30:00+08:00",
    "product": {
      "productId": "35",
      "productCode": "MBA",
      "productName": "亮甲2.0"
    },
    "phoneModel": {
      "phoneModelId": "88",
      "brandName": "示例品牌",
      "modelName": "示例型号",
      "phoneModelCode": "1199"
    },
    "historicalPhoneModelCode": null,
    "applicationMoldCodes": ["MBA101199", "MBA201199"],
    "moldSetId": "501",
    "itemId": "300",
    "moldParts": [
      {"partType": "TPU", "materialCode": "10", "moldCode": "MBA101199"},
      {"partType": "PC", "materialCode": "20", "moldCode": "MBA201199"}
    ],
    "skuCodes": [],
    "businessStatus": "approved",
    "executionStatus": "active",
    "provisioningStatus": "completed",
    "progress": {
      "scheduleStatus": "shipped",
      "scheduleStatusText": "已发模",
      "asOfDate": "2026-11-06"
    },
    "expectedDeliveryDate": "2026-11-20",
    "dates": {
      "openedDate": "2026-10-20",
      "plannedMoldDeliveryDate": "2026-11-01",
      "plannedFactoryArrivalDate": "2026-11-20",
      "actualShipDate": "2026-11-02",
      "actualMexicoArrivalDate": null,
      "actualPortArrivalDate": null,
      "pcFactoryArrivalDate": null,
      "tpuFactoryArrivalDate": null
    },
    "arrivalWritableFields": ["actualPortArrivalDate"]
  }
}
```

上述状态值用于说明分层结构，实际枚举须以现有状态定义核对、冻结后为准。未建档申请返回 `moldSetId: null`、`moldParts: []`、`arrivalWritableFields: []`，实物日期及实物进度为空；申请自身的状态、编码和期望交期仍可展示。

## 6. 列表查询与首次同步

`GET /api/integrations/v2/crm/opening-applications`

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `pageSize` | 否 | 默认 50，范围 1–100 |
| `pageToken` | 否 | 不透明的下一页令牌；首屏不传 |
| `recordId` / `approvalNo` | 否 | 精确查询申请 |
| `productCode` / `phoneModelCode` / `phoneModelId` | 否 | 产品、标准机型精确筛选 |
| `moldCode` / `skuCode` | 否 | 按已确认关联的完整编码精确筛选；模具码也匹配申请预留码 |
| `businessStatus` | 否 | 申请业务状态，合法值待枚举表冻结 |
| `keyword` | 否 | 产品/型号名称或申请单号的模糊搜索；不作为身份匹配依据 |

多个参数按 AND 组合，不能只满足型号就忽略产品。`phoneModelCode` 仅匹配标准码；历史原码不作为标准码的替代命中。后续页须保持相同筛选条件、账号和页大小，令牌不匹配返回参数错误。

列表中每条 `items` 使用第 5 节的记录结构。默认按数值型申请 ID 升序进行游标分页，不使用会因更新时间移动的 offset 翻页。

响应结构示例（空集合）：

```json
{
  "data": {
    "items": [],
    "hasMore": false,
    "nextPageToken": null,
    "syncCursor": "sync-start-example"
  }
}
```

首次同步流程：

1. CRM 不带业务筛选查询全部授权记录。PLM 在读取第一页数据前取得增量起点 `S0`，并固定本次扫描的最大申请 ID；`syncCursor` 返回该起点，翻页令牌携带扫描边界。
2. CRM 逐页按唯一键 upsert，直到 `hasMore=false`。分页读取是实时数据，不声称所有页面来自同一个数据库快照。
3. CRM 使用最初的 `S0` 消费第 9 节增量。扫描期间新增、修改、删除和关联变更都必须可补偿；允许重复读取，不能漏记录。扫描期间新建且超出扫描上界的申请由增量补入。
4. 只有全量和后续增量均成功处理，才将本轮状态标记为已同步。带业务筛选的列表用于 CRM 界面检索，不能作为全范围同步完成的依据，其 `syncCursor` 返回 null。

`syncCursor` 有效期拟定 7 天，`pageToken` 有效期拟定 24 小时；过期返回 `410`，CRM 重新启动扫描。正式时限需结合数据量确认，不能在读取完之前无提示回收必要变更。

## 7. 实际到达日期局部回写

`PATCH /api/integrations/v2/crm/opening-applications/{recordId}/arrival-dates`

请求头：

```http
Authorization: Bearer <crm-service-token>
Content-Type: application/json
If-Match: "rv-example-17"
Idempotency-Key: crm-arrival-1024-20261106-001
```

请求示例仍假定 `actualPortArrivalDate` 已获得业务授权：

```json
{
  "moldSetId": "501",
  "dates": {
    "actualPortArrivalDate": "2026-11-06"
  },
  "reason": "CRM 根据物流到港确认记录回传",
  "crmReference": "CRM-ARRIVAL-0001",
  "operator": {
    "id": "crm-user-100",
    "name": "示例操作人"
  }
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `moldSetId` | 是 | 必须等于当前申请关联的实物套；防止关联改变后写错对象 |
| `dates` | 是 | 至少一个已授权日期键，禁止提交空对象 |
| `reason` | 是 | 非空原因；首次填写、修改、清空统一要求留痕 |
| `crmReference` | 是 | CRM 业务记录引用，用于审计检索，不替代幂等键 |
| `operator` | 否 | CRM 操作人辅助信息，不作为认证身份 |

保存语义：

- **未出现的键保持不变。** 提交 `"actualPortArrivalDate": null` 表示明确清空；清空与改值使用相同权限和校验，空字符串不是清空指令。
- 不接收完整记录快照。未知属性、只读日期、计划、状态及白名单外日期一律拒绝，不能忽略后返回成功。
- 单次请求为一个事务：所有日期全部成功或全部回滚。保留现有日期格式、前后顺序、部件存在性、执行状态和业务前置条件校验；本版不额外臆定“到港必晚于抵墨”等跨节点规则。
- 禁止因为 CRM 写入日期而自动通过验收、改变审批结论或绕过现有量产/上市前置条件。进度由 PLM 现有规则重新计算。
- 已删除、不可执行、未建档、关联不符、无写权限的对象拒绝写入；不能自动建档或创建缺失部件。
- 若所有提交值与当前值相同，可返回成功，不制造无意义的新业务版本或重复审计变更；仍记录幂等结果。

成功返回 `200`，响应体为第 5.4 节的**保存后完整详情结构**，头部返回最新 `ETag`。CRM 用返回的正式值和版本刷新界面；未提交日期仍保持原值。共享实物关联的申请随后均可通过查询获得新结果。

## 8. 并发、幂等与超时

### 8.1 对外版本

`recordVersion` 是不透明的聚合版本，CRM 只能原样传回，不能转整数、大小比较或自行加一。详情的 `ETag` 为该版本加双引号的 HTTP 表示，PATCH 的 `If-Match` 必填，不接受 `*`。

版本应覆盖该申请对外投影及回写依赖：申请状态及关联、关联实物套和部件日期、展示编码及权限相关状态等。共享模具日期变化也使相关申请的版本失效。读数据和读版本必须一致，写操作必须原子地完成版本与关联检查、业务校验及保存，不能“先查版本、之后无锁覆盖”。

源码目前分别存在申请版本、主档版本、节点版本及内部字段旧值校验，均不能直接冒充此聚合版本。旧推送事件的 `recordVersion` 也不自动满足本接口条件。具体版本持久化和依赖更新机制需在后续实现方案中新增。

版本不符返回 `412 VERSION_CONFLICT`，不修改任何字段。CRM 查询最新详情，让操作人核对后用新版本和新幂等键再次提交，不得自动以旧值覆盖新值。

### 8.2 幂等

`Idempotency-Key` 必填，建议使用不超过 128 字符的 UUID 或唯一业务请求号。同一服务主体下，一个键只能对应同一方法、路径、`If-Match` 及规范化请求内容。幂等记录保留期拟定至少 7 天，CRM 重试窗口不能超过该期限。

PLM 校验身份后，先识别已提交的幂等结果，再做新请求的版本校验。同键同内容重试返回原成功结果，即使原版本已经失效；同键改内容返回 `409 IDEMPOTENCY_KEY_REUSED`。同键并发执行必须串行化，业务保存和成功幂等结果在同一事务提交，不能因超时重复改值或重复记历史。

幂等重放结果反映原次保存时刻，可能不是当前最新版本；CRM 如需当前数据，再 GET 详情。明确未提交的校验错误不作为成功结果缓存，修正后建议使用新键。

### 8.3 CRM 的超时处理

请求超时不代表 PLM 保存失败。CRM 保留原请求、原版本和原幂等键进行重试，并查询详情确认现状，不能直接生成新键盲目重写。冲突、鉴权失败和业务校验失败不进行无条件自动重试；限流或暂时不可用时按 `Retry-After` 退避。

## 9. 增量更新

`GET /api/integrations/v2/crm/opening-application-changes?cursor={cursor}&pageSize=100`

`cursor` 必填，来自全量起点或上一页 `nextCursor`。`pageSize` 默认 100，范围 1–100。首期建议 CRM 每 60 秒轮询，可按双方时效及容量调整；这意味着最终一致，不承诺实时推送。

```json
{
  "data": {
    "changes": [
      {
        "changeId": "90001",
        "recordId": "1024",
        "changeType": "UPSERT",
        "changedAt": "2026-11-06T14:35:00+08:00"
      },
      {
        "changeId": "90002",
        "recordId": "1025",
        "changeType": "REMOVE",
        "changedAt": "2026-11-06T14:36:00+08:00"
      }
    ],
    "hasMore": false,
    "nextCursor": "sync-checkpoint-example"
  }
}
```

这是变更索引，不是整表快照：`UPSERT` 表示重新查询该申请详情；`REMOVE` 表示已删除或已移出该服务主体的可见范围，CRM 移除相应本地展示，不要求 PLM 物理删除。记录后来恢复可见时再产生 `UPSERT`。

契约要求：

1. 使用提交可见顺序的变更日志和不透明游标，按 `changeId` 数值顺序读取。不能只查询申请 `updated_at > 上次时间`，也不能直接把可能倒序提交的数据库序列当成无遗漏水位。
2. 每轮捕获固定上界，后续页沿用上界，新变更留给下一轮。没有变更也返回有效 `nextCursor`。CRM 只在该页变化已可靠落地后保存游标，失败重放允许重复处理。
3. 同一记录一页出现多次时，CRM 可按最后一个变化合并处理；跨页仍按顺序消费。详情可比变更发生时更新，返回当前正式数据，不承诺历史快照。UPSERT 后详情已为 404 时按移除处理。
4. 覆盖申请提交、审批/建档/执行变化、PLM 内部保存、批量保存、导入、相关联的编码/型号/SKU变化、CRM 日期回写、删除、恢复以及可见范围变化。共享模具变更要覆盖所有受影响申请。
5. 若提供随自然日期变化的延期/超期等计算结果，PLM 按业务时区刷新并为变化记录生成增量，不能依赖当天有人工保存。状态含义与计算日期同时返回。
6. 游标绑定服务主体、授权范围和契约版本，不接受任意型号筛选的局部消费。范围调整不能可靠产生补偿时，应使游标失效并要求重新全量。
7. 游标或所需变更已过保留期时返回 `410 CURSOR_EXPIRED`。CRM 重新全量，将旧本地记录标记为待核对；新扫描及补偿完成后再清理未重新出现的旧记录，避免全量失败时清空展示。

该变更日志、提交顺序水位及范围补偿能力尚需实现。现有推送 Outbox 不能直接宣称已覆盖这些要求。

## 10. 错误响应

错误体统一采用下列拟定结构，不把业务失败包装成 HTTP 200：

```json
{
  "error": {
    "code": "VERSION_CONFLICT",
    "message": "记录已更新，请重新查询后提交",
    "fields": []
  },
  "requestId": "req-example-001"
}
```

| HTTP | 错误码示例 | 处理 |
| --- | --- | --- |
| 400 | `INVALID_ARGUMENT` | 参数、未知字段、空补丁或日期格式不正确 |
| 401 | `UNAUTHENTICATED` | 凭证无效或过期 |
| 403 | `FORBIDDEN` / `FIELD_NOT_WRITABLE` | 缺写权限或日期不在白名单 |
| 404 | `NOT_FOUND` | 不存在、已删除或不可读取，不泄露越权对象信息 |
| 409 | `TARGET_NOT_READY` / `TARGET_CHANGED` | 未建档、不可执行或关联目标不符 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 相同幂等键对应不同请求 |
| 410 | `CURSOR_EXPIRED` / `PAGE_TOKEN_EXPIRED` | 重建同步或翻页会话 |
| 412 | `VERSION_CONFLICT` | 乐观并发冲突，无字段写入 |
| 422 | `BUSINESS_RULE_VIOLATION` | 违反现有业务前置条件，返回可公开的字段原因 |
| 428 | `PRECONDITION_REQUIRED` | 缺少 `If-Match` |
| 429 / 503 | `RATE_LIMITED` / `TEMPORARILY_UNAVAILABLE` | 按 `Retry-After` 退避重试 |

服务端未处理异常返回 500，携带跟踪标识，不暴露堆栈、SQL 或内部建档异常详情。错误映射后续在新增接口层完成，不改变老接口响应结构。

## 11. 调用链路与受影响边界

```text
CRM 列表/详情 → PLM 新增集成查询入口 → 申请 + 已确认产品/机型/编码关系
                                      → 关联模具套/PC、TPU进度 → 对外字段投影

CRM 日期编辑 → 查询最新详情及版本 → PATCH 新增集成入口
             → 身份/范围/字段权限 → 幂等处理 → 原子版本及关联检查
             → 复用现有业务校验和正式保存 → 审计及变更索引 → 返回保存结果

PLM 内部节点变化 / CRM 日期保存 → 受影响申请变更索引
                              → CRM 增量轮询 → GET 最新详情 → 刷新展示
```

后续代码方案应以新增集成 Controller、DTO、查询投影、回写适配及变更索引为主。内部保存路径仅补充必要的版本/变更记录分支，保持原页面 API 契约；禁止把内部任意字段批量修改能力暴露出去。

| 源码参考 | 当前用途及新方案边界 |
| --- | --- |
| `plm-server/src/main/java/com/yuewei/plm/module/moldapplication/MoldApplicationService.java` | `progressPage` 及申请状态、标准机型、编码关联的来源；需核对服务账号可见规则 |
| `plm-server/src/main/java/com/yuewei/plm/module/collaboration/MoldScheduleController.java` | 当前内部页面查询/保存路由，仅作参考，保持老接口 |
| `plm-server/src/main/java/com/yuewei/plm/module/collaboration/MoldScheduleService.java` | 模具套详情及正式排程业务入口，回写适配须复用业务约束 |
| `plm-server/src/main/java/com/yuewei/plm/module/collaboration/CollaborationRepository.java` | 模具套、PC/TPU部件日期和已有关系查询来源，不将内部行原样外传 |
| `plm-server/src/main/java/com/yuewei/plm/module/collaboration/MoldScheduleTypes.java` | 日期修订及节点前置条件相关规则参考 |
| `plm-web/src/views/schedule/MoldScheduleView.vue` | 核对申请页展示和不同实际到达字段的中文含义；本次不改页面 |
| `plm-server/src/main/java/com/yuewei/plm/module/integration/crm/moldschedule/` | 旧推送实现，不满足本版查询/回写契约；不因本次文档更新而启用、删除或改造 |

旧实现对外推送的身份、版本和认证不能直接套入新版。上线前须核查旧推送配置，避免同时采用两套含义不同的同步协议。本文件不授权切换任何运行配置。

## 12. 验收与测试方法（待实现后执行）

| 场景 | 操作与预期 |
| --- | --- |
| 未建档申请 | 列表和详情能查询申请编码、业务状态；无实物日期编辑权限，PATCH 不自动建档 |
| 型号与产品定位 | 组合筛选仅返回同时匹配记录；`0185` 前导零保留，标准码为空不拿历史码替代 |
| 共模/多申请 | 两张申请关联同一套；回写任意获授权申请后，两张详情一致且均进入增量 |
| 局部更新 | 仅提交一个授权日期；其他日期、计划、审批状态不被覆盖 |
| 显式清空 | 提交 null 且有原因后清空对应日期；未出现的键保持原值 |
| 权限及业务规则 | 提交白名单外、只读、未知字段或违反前置条件；整次请求失败且无部分写入 |
| 并发修改 | 取详情后由 PLM 页面修改相关值；旧版本 PATCH 返回 412，不覆盖页面结果 |
| 目标改变 | 查询后变更申请关联套；旧请求被版本或目标校验拒绝，两个实物均无误写 |
| 幂等和超时 | 相同请求同键并发/超时重试，只提交一次业务变更；原结果可重放，改内容被拒绝 |
| 全量期间变化 | 扫描中新增、修改、删除、恢复记录；完成 S0 增量补偿后 CRM 与当前 PLM 一致 |
| 内部节点完成 | PLM 页面保存、导入或完成节点后，CRM 下一轮增量及详情反映新进度 |
| 游标边界 | 同时刻多次保存、事务提交顺序不同、跨页更新、空页轮询均无遗漏；过期触发全量 |
| 数据范围变化 | 撤销可见范围后 CRM 收到移除或明确重建要求；越权查询和回写均被拦截 |
| 保存可见性及审计 | PATCH 后 PLM 页面可见正式值，历史含前后值、原因、CRM来源和请求引用 |
| 老功能回归 | 原申请列表、模具进度、内部批量保存/导入接口仍按原契约工作 |

当前仅完成文档设计，上述测试尚未执行，不能据此声明接口已可调用。

## 13. 契约冻结前待确认

1. **实际要传哪些表单列？** 请用中文列名确认最小字段集；第 5 节为候选，不默认外传整表或附件。
2. **“实际到达时间”具体是哪一列？** 实际抵墨、实际到港、PC 到厂、TPU 到厂中的哪项由 CRM 维护；是否允许修改及清空，PLM 内部是否也可修订。
3. **查询范围是否采用申请维度？** 是否展示拒绝/终止、未建档、历史补链及其他来源申请；共模共享日期的展示与编辑提示是否接受。
4. **需要哪些编码？** 产品码、标准机型码、申请模具码、实物部件码为建议基础；是否确需 SKU、外部货号及 CRM 映射 ID。
5. **联调参数及枚举。** PLM 环境地址、CRM 服务账号/数据范围、实际状态枚举、轮询频率、游标和幂等保留期。

上述关键业务项确认后再冻结可开发契约，并据此更新代码改造方案。旧 v1.0 及基于旧推送方向的改造方案不再作为新版开发依据。
