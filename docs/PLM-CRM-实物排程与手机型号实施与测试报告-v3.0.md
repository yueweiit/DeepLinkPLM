# PLM–CRM v3.0 实施与测试报告

日期：2026-11-09。源码：D:/work/PLM_new/DeepLinkPLM-main。

接口对接请使用 [最终接口文档](./PLM-CRM-实物排程与手机型号最终接口文档-v3.0.md)。旧方案/设计稿保留为决策记录，类名、状态码和部署细节以本报告及最终文档为准。

## 2026-11-10 本地运行服务启用补充

以下为后续本地启用记录；下文 2026-11-09 的“未对运行服务执行”描述保留为当时交付事实，不代表当前本地状态。

- 环境：`D:/work/PLM_new/20261017-c856_bHNzr/local-runtime` 中的 `plm-local-20261106` Docker 项目及本地恢复数据库；未连接或修改生产环境。
- 现有应用镜像已经包含 CRM v3 模块和两项 CRM 迁移，故未替换镜像或运行 JAR。JAR SHA-256 前后一致：`804f0f4cf6177fcb0f4e97671c658b6c828a9453ee4f78a04009d8df63f38b25`。123 项迁移验证通过，没有新增迁移。
- 备份：上述目录下 `crm-openapi-release-20261110/`，保存原运行 JAR、原 compose 与本地数据库自定义格式备份；备份目录检查通过，含私有业务数据，不用于公开交付。
- 账号：新建 `crm-local-service`（ID 14），非管理员、无网页登录密码，domestic / all_objects=true，权限仅 schedule:read；没有修改原账号。
- 初始化：原 control 为 ready=false / capture_enabled=false，首次初始化后两项均为 true。索引为 42 套 ACTIVE 实物、1393 个 ACTIVE 手机型号及 3 个 INACTIVE 手机型号，没有导入历史快照。
- 配置：加载本地 `.env.crm-openapi.plm.local`；enabled=true，write-enabled=false，仅含两项 read scope。实际 Token 原文只在 CRM 私有交付文件中，不写入本文。
- 网络：新增 CRM 专用 Nginx 网关，仅绑定 `192.168.5.71:8080` 并代理 `/api/integrations/v3/crm/`；其他路径 404。原前端保留 `127.0.0.1:8111`，后端仍在 Docker 内部网络。没有修改防火墙，尚未从 CRM 电脑实测。
- 操作：仅短暂停止/重建本地后端并新增网关；未重启数据库、Redis 或原 Web 容器，未修改排程日期。后续应使用 `start-crm.cmd` 或 `docker compose -f compose.yaml -f compose.crm-openapi.yaml ...` 保留覆盖配置。
- 验证：`test-crm-readonly.ps1` 的 15 项检查通过，覆盖原前端与健康、有效/无效鉴权、型号分页与详情、排程查询与详情、ETag、增量游标、未知参数拒绝、只读写入拒绝以及网关隔离。有效查询 200，缺少/错误 Token 401，日期 PATCH 403，非 CRM 路径 404。
- 待验收：CRM 所在电脑的网络连通、CRM 程序联调以及日期回写启用/验收。本次只读启用不代表写入功能或生产验收已完成。

## 1. 交付结论与效果

已按“新增独立模块 + 原保存逻辑窄分支 + 事务捕获 + 顺序增量”实施：

| 需求 | 已实现效果 |
| --- | --- |
| 实物排程查询 | 列表/详情以 moldSetId 为身份，跨全部产品，返回约定表格字段、编码、状态和延期 |
| 三日期回写 | 实际抵墨、计划验收、计划试模量产可局部修改及 null 清空；未提交项不变 |
| PLM 状态 | 复用正式排程计算；计划不冒充实际完成，不覆盖更后续验收事实 |
| PLM 人工修改 | 仍走原入口，改变同一正式值；CRM 旧 ETag 不可覆盖新值 |
| 型号及编码 | 无申请/模具的新标准型号可查；无编码返回 null，0185 等前导零保留 |
| 删除/停用/恢复 | 对外发布 DELETE、DISABLE、ENABLE；不提供历史整行快照 |
| 自动更新 | CRM 从统一 /changes 获取标记并查询最新详情，不是主动网络推送 |
| 安全 | 新命名空间强制独立凭证、scope 和最小权限真实账号；旧安全关闭也不放开新接口 |
| 原接口兼容 | 未修改老 Controller 路径/参数/响应，未改前端，未启用或删除旧推送 |

当前是“源码完成、隔离验证完成”，不是“现有生产服务已开放”。业务数据库未执行本次迁移，未创建生产账号、发放凭证、修改现有服务配置、重启服务或实现 CRM 侧程序。

## 2. 修改文件清单

### 2.1 原文件的窄改动

| 文件 | 修改内容 |
| --- | --- |
| plm-server/src/main/java/com/yuewei/plm/module/collaboration/CollaborationService.java | 新增 LockedRowPrecondition 和 saveCrmScheduleDates 内部入口；要求外层事务，限定三字段，锁后取 before 并执行版本 guard，再调用原 saveRow；无变化直接返回当前行 |
| plm-server/src/main/resources/application.yml | 新增 plm.integrations.crm.openapi 配置；enabled/write-enabled 均默认 false，秘密仅外部注入 |

没有改写原 save/batch-save 的部分成功语义，没有把任意字段保存接口授权给 CRM。新入口不是新增到老 Controller 的 HTTP 路由。

### 2.2 新增业务模块

目录：plm-server/src/main/java/com/yuewei/plm/module/integration/crm/openapi/。

| 文件 | 责任 |
| --- | --- |
| CrmOpenApiController.java | 六个 CRM HTTP 入口，data 包装及详情 ETag |
| CrmOpenApiSecurityConfig.java | 优先级独立安全链、服务 Token 摘要、真实账号/范围/职责检查、线程身份清理 |
| CrmOpenApiProperties.java | 默认关闭配置及完整性检查 |
| CrmServicePrincipal.java | 服务身份、scope 和请求上下文 |
| CrmQueryService.java | 白名单查询投影、过滤/keyset 分页、调用原排程规则，不外传费用/整表单 |
| CrmScheduleDateService.java | 严格 JSON、null 语义、事务、幂等、版本检查、原保存桥接及有限重试 |
| CrmSyncStore.java | 初始化状态、资源版本、签名同步游标、稳定增量分页 |
| CrmTokenCodec.java | HMAC-SHA256 签名令牌及摘要，不接受客户端生成的未签名游标 |
| CrmChangeJobs.java | 每秒有序发布、每分钟分批日切及保留期清理 |
| CrmApiException.java | CRM 专用错误状态/代码 |
| CrmApiErrors.java | 仅匹配新 Controller 的异常转换，不接管旧接口 |

相比原计划将 DTO/投影/版本/幂等拆为多个子包，本次合并为 11 个类及局部 record，降低新增文件和注入链复杂度；不影响对外接口边界。

### 2.3 新增迁移

目录：plm-server/src/main/resources/db/migration/。

| 文件 | 内容 |
| --- | --- |
| V20261109_1900__crm_openapi_sync_foundation.sql | 新增 control、resource、capture、change、idempotency 五张表，初始化 ready=false、capture_enabled=false |
| V20261109_1910__crm_openapi_source_capture.sql | 生命周期、资源修订、依赖扇出函数，13 个源表 AFTER ROW 触发器及 operator-only 初始化函数 |

触发器覆盖：实物套、部件、协作档案、项目关联、手机成员关联、套级兼容关系、开模申请、标准型号、品牌、编码项、产品、模具编码和负责人账号显示资料。

触发只观察投影依赖列的实际变化；OLD/NEW 均参与影响集合计算，覆盖移除/改绑。只存资源身份、修订、动作和时间，不存源行、密码或凭证。触发器不会反向写入业务表，不进行 HTTP 调用。

数据库触发器会进入已有写事务：开启捕获后有新增锁、扇出耗时及失败导致源事务回滚的影响，不能表述为“完全不影响原系统”。

### 2.4 新增测试与文档

| 文件 | 用途 |
| --- | --- |
| plm-server/src/test/java/com/yuewei/plm/module/integration/crm/openapi/CrmOpenApiUnitTest.java | 字段/null、严格 JSON、令牌、规范化、服务身份和最小权限 |
| 同目录 CrmOpenApiSecurityTest.java | 两套真实 Spring Security 链共存、旧安全关闭、scope、旧路由拒绝服务 Token |
| 同目录 CrmOpenApiPostgresTest.java | opt-in 独立 PostgreSQL 实际保存/审计/回滚/触发器/并发/生命周期/分页 |
| scripts/test_crm_openapi.ps1 | 创建独立临时数据库、恢复纯结构、应用新迁移、运行专项测试并清理自身容器 |
| docs/PLM-CRM-实物排程与手机型号最终接口文档-v3.0.md | 供 CRM 按实际实现对接 |
| 本文件 | 改动清单、部署说明、测试证据和剩余验收 |

## 3. 调用链及受影响模块

```text
CRM Token
  → 专用 SecurityFilterChain → 真实非管理员账号/全部范围/scope
  ├─ 排程 GET → 新白名单投影 → 原 MoldScheduleService / 排程政策
  ├─ 型号 GET → 标准型号 + 品牌 + 当前编码
  ├─ 日期 PATCH → 严格白名单 → 幂等事务锁
  │             → 原执行锁/套锁 → 资源锁 + ETag/lifecycle
  │             → CollaborationService 新桥接 → 原 saveRow
  │             → 正式字段 + 原历史/审计 + 同事务 capture
  │             → 保存后投影 + 成功幂等结果，一起提交
  └─ /changes → 已提交的顺序发布日志

PLM 页面/导入/申请/主数据等正式 DML
  → 源表触发器 → resource 修订 + 最小 capture，同事务提交/回滚
  → Publisher 锁发布头 → 顺序 change_id + 标记已发布 + 水位一起提交
  → CRM 轮询 → 最新详情
```

| 模块 | 影响 |
| --- | --- |
| 原协作/排程 | 仅新增内部保存入口，复用日期、部件、执行、历史及审计逻辑 |
| 型号/品牌/编码/产品/申请/导入/人员 | 未改原 Java API，但捕获开启后其依赖 DML 会增加资源修订和触发器工作 |
| 认证 | 新命名空间独立链；旧链不变，未全局注册新 Filter |
| 原前端及 CRM 侧 | 本次未修改；PLM 刷新原页面读取同一正式日期 |
| 定时任务/数据库 | 新增发布、日期补偿、清理；需运维监控 |
| 旧 CRM 推送 | 源码和配置保留，不复用其版本/Outbox，也不因本次工作启用 |

重要落地细节：

- 查询/版本在 REPEATABLE READ 事务内读取；增量边界与日志也在同一读事务内，避免清理竞态。
- PATCH 全事务最多重试两次争用失败，每次重新检查幂等和原版本；不重试成新版本强制覆盖。
- 同键成功结果在版本检查前返回，解决首次成功后原 ETag 已变化的问题。
- Publisher 不以 capture 最大号作为 checkpoint；未提交小号不会被漏掉。
- 受影响资源在每次源触发中按 (entityType, id) 去重排序，但多行/多表业务事务仍可能有锁竞争，不能替代全矩阵压测。
- 日切按每分钟最多 200 套可用资源分批补偿，同日重跑不重复生成同日修订。
- 原方案中的在线分批首次初始化，落地为维护窗口内锁表初始化；以一致性为优先，不声称无阻塞。具体见下节。

## 4. 部署与启用（本次未对运行服务执行）

### 4.1 配置和账号

| 环境变量 | 默认 / 要求 |
| --- | --- |
| PLM_CRM_OPENAPI_ENABLED | false；同时控制新 API 和新发布/维护定时任务 |
| PLM_CRM_OPENAPI_WRITE_ENABLED | false |
| PLM_CRM_OPENAPI_SUBJECT_ID | crm，稳定服务主体标识 |
| PLM_CRM_OPENAPI_SERVICE_USER_ID | 0；须替换为专用真实账号 ID |
| PLM_CRM_OPENAPI_TOKEN_DIGESTS | 空；一个或多个 SHA-256 十六进制摘要，逗号分隔 |
| PLM_CRM_OPENAPI_SIGNING_SECRET | 空；至少 32 字符的独立高强度秘密，用于游标/版本 |
| PLM_CRM_OPENAPI_SCOPES | crm:mold-schedule:read,crm:phone-model:read；开放写入时追加 crm:schedule-dates:write |
| PLM_CRM_OPENAPI_CHANGE_RETENTION_DAYS | 30 |
| PLM_CRM_OPENAPI_IDEMPOTENCY_RETENTION_DAYS | 7，不能低于 7 |

Publisher/maintenance 间隔还可通过 plm.integrations.crm.openapi.publisher-interval-ms / maintenance-interval-ms 属性配置，默认 1000 / 60000 毫秒，不是本次显式新增的环境变量占位项。

使用原账号管理能力建立启用、未删除、非管理员账号；role=domestic、all_objects=true。权限限定为最终文档的五项以内，至少 schedule:read；开放写入另授 schedule:write 及 logistics/mexico 职责。专用账号不是管理员替身，不向 CRM 发放其网页登录密码或数据库凭证。

服务 Token 推荐至少 32 随机字节，仅安全交付 CRM；PLM 保存其 SHA-256 摘要。签名秘密与 Token 分开管理，不能写入仓库、示例文件或普通日志。更换签名秘密会使旧游标无法验签，应安排 CRM 重新全量同步。

### 4.2 发布顺序

1. 备份并审核目标 schema/迁移版本。新接口保持关闭，沿原发布流程部署后端和两份新增 Flyway 迁移，不修改已执行的迁移，不对准服务器部署文件夹改源码。
2. 核对迁移安装的 search_path 与真实业务 schema；当前验证 schema 为 plm。确认网络代理转发 /api/integrations/v3/crm/**，PATCH 和认证头可通；正式环境启用 HTTPS/访问控制。
3. 维护窗口内暂缓相关业务写入和新定时任务，确认没有并发发布/日切，再以受控数据库运维身份初始化。
4. 配好专用账号、凭证及读 scope，先打开 enabled、保持 write-enabled=false，验证列表/详情/增量和告警。
5. CRM 完成当前全量 + S0 后补偿，确认身份、字段及启停处理。
6. 双方验收后开启写 scope 和 write-enabled，验证三个日期回写，再按正式发布流程放量。

初始化示意（不是日常定时任务，禁止每次启动重复调用）：

```sql
begin;
set local search_path = plm, public;
set local lock_timeout = '5s';
set local statement_timeout = '5min';
select plm_crm_openapi_initialize();
commit;
```

初始化会以 SHARE ROW EXCLUSIVE 锁定 13 个源表、重置同步世代/发布水位并扫描当前对象，完成后才设置 ready/capture_enabled。锁失败整事务回滚，应重新安排窗口；不要去掉超时后在生产无限等待。重新初始化会使 CRM 旧游标返回 410。

### 4.3 监控和回退

只读运维查询示意：

```sql
set search_path = plm, public;
select ready,capture_enabled,stream_epoch,published_head,retained_after,last_business_date
from plm_crm_openapi_control;
select count(*) pending,min(captured_at) oldest_pending
from plm_crm_openapi_capture
where not published and stream_epoch=(select stream_epoch from plm_crm_openapi_control);
select count(*) awaiting_day_advance
from plm_crm_openapi_resource
where entity_type='MOLD_SET' and lifecycle='ACTIVE'
  and business_date is distinct from (clock_timestamp() at time zone 'Asia/Shanghai')::date;
```

还应监控服务 5xx/412/幂等冲突、数据库锁等待、源表扇出耗时、发布延迟及日切完成度。ready 仅表示初始化完成，不自动诊断 worker 健康。

回退先关闭 write-enabled，再关闭 enabled；旧业务接口保持原契约。关闭 enabled 也会停止新后台任务，但捕获可继续积压，必须监控并安排恢复。已经成功写入的正式日期不自动撤销，不清空幂等和未消费记录。

若因故障必须停止捕获，应同时停止对外、ready=false，并在受控维护中重新初始化后恢复，要求 CRM 全量重建；不能漏采后持旧游标继续。不得自动删除业务日期、原审计或回滚已执行迁移。

## 5. 已执行测试及结果

### 5.1 环境和专项测试

Windows、Java 17、Maven 3.9.9、独立 PostgreSQL 17。只从已有本地数据库读取 schema-only 结构，未复制业务数据；新迁移和测试数据仅写独立容器数据库 crm_openapi_it，绑定 127.0.0.1:15519。

2026-11-09 最后一次一键脚本执行结果：

| 测试 | 数量 | 结果 |
| --- | --- | --- |
| CrmOpenApiUnitTest | 7 | 全通过 |
| CrmOpenApiSecurityTest | 3 | 全通过 |
| CrmOpenApiPostgresTest | 11 | 全通过，未跳过 |
| MoldScheduleServiceTest | 9 | 全通过 |
| MoldSchedulePolicyTest | 12 | 全通过 |
| MoldExecutionPolicyTest | 3 | 全通过 |
| SecurityConfigTest | 1 | 通过 |
| 合计 | 46 | failures=0、errors=0、skipped=0，BUILD SUCCESS |

专项覆盖了三日期保存/清空/no-op、原 PLM 保存入口修订和旧 ETag 冲突、历史不重复、同键并发、编码 null/0185/停用、父级生命周期传播、暂停、业务回滚、较小 capture 晚提交、日切重跑、分页/搜索/游标清理边界、物理删除标记、移除关系扇出，以及使用真实数据库执行服务账号范围检查。

原 OperationLogService 在隔离 service 测试中使用 mock；原协作历史写入使用真实数据库并检查。Spring Security 测试使用实际两条过滤链与 MockMvc，部分查询返回使用 mock；不等同于已访问生产网关或 CRM 实际系统。

两份新增迁移均在克隆结构上以 ON_ERROR_STOP 执行成功。一键测试脚本也从全新容器跑通并自动清理自身；本次创建的测试容器及其临时测试数据已删除，未删除原业务数据。

后端编译及 mvn -f plm-server/pom.xml -DskipTests package 成功，构建产物为 plm-server/target/plm-server-0.0.1-SNAPSHOT.jar；未复制到运行目录或重启服务。临时库数据不保留备份，测试 fixture 可用一键脚本重新生成。

### 5.2 全量回归结果

扩展执行过 mvn -f plm-server/pom.xml test。该次运行（追加最后一个真实账号用例前）的结果：

- 总计 2153 项：1198 通过、952 跳过、0 断言失败、3 环境错误。
- 三个错误均发生在 Files.createSymbolicLink，Windows 当前账号缺少创建符号链接权限：

  - MasterDataSnapshotTest.localStoreChecksHashCountsAndRejectsPathOrSymlink
  - MoldSalesArchiveStoreTest.aSymbolicFileCannotEscapeThePreparedDirectory
  - MoldSalesHistoryReviewStoreTest.preparedFilesMustHavePinnedHashNameAndCannotEscapeThroughSymlinks
- 未修改以上无关原测试或系统权限来绕过问题，因此不能将全量回归写成“全部通过”。
- 原 CollaborationLocalIntegrationTest、PhoneModelReferenceLocalIntegrationTest、ProductMoldLifecycleLocalIntegrationTest 等有各自隔离数据库地址门槛，本次未配置其专用环境，因此跳过不计通过。

随后对最终代码重新执行上节专项 46 项，全部通过。测试结果文件位于 plm-server/target/surefire-reports；后续只跑专项会更新其中同名报告，其他报告仍保留对应全量运行结果。

## 6. 如何复跑

### 6.1 一键隔离测试（推荐）

准备来自相同 PLM schema 的 pg_dump --schema-only SQL 文件，不能使用含业务数据的备份；测试脚本不会连接源业务库。需要 Docker、Java 17、Maven，15519 端口必须空闲。

```powershell
.\scripts\test_crm_openapi.ps1 -SchemaDumpPath 'D:\test-data\plm-schema-only.sql'
```

Maven 或镜像需要指定时：

```powershell
.\scripts\test_crm_openapi.ps1 -SchemaDumpPath 'D:\test-data\plm-schema-only.sql' -MavenCommand 'D:\tools\apache-maven\bin\mvn.cmd' -PostgresImage '你的可信镜像仓库/postgres:17-alpine'
```

脚本生成唯一容器 ID、只绑定本机端口、以 tmpfs 保存测试库；恢复纯结构后应用 CRM 基础、捕获及产品/SKU 拆分三份迁移并运行专项。正常/失败均在 finally 清理本次创建的确切容器，恢复进程环境变量，不枚举删除其他容器。需要排错可传 -KeepTestContainer；此时须自行受控清理该临时容器。不要让测试 trust 数据库暴露到外网。

PG 测试严格只接受：

```text
PLM_CRM_OPENAPI_TEST_DB_URL=jdbc:postgresql://127.0.0.1:15519/crm_openapi_it?currentSchema=plm
```

测试会提交自身 fixture，需要全新空结构，每次重复直接跑 PG 测试前须重建独立测试库；一键脚本已处理。不同地址不会转而测试业务库，而是跳过该测试。

不启用数据库只跑单元/安全/原政策测试：

```powershell
mvn -f plm-server/pom.xml '-Dtest=CrmOpenApiUnitTest,CrmOpenApiSecurityTest,MoldScheduleServiceTest,MoldSchedulePolicyTest,MoldExecutionPolicyTest,SecurityConfigTest' test
```

### 6.2 联调验收步骤

1. 在测试环境完成第 4 节部署/初始化，GET 一套详情，保存 ETag、七个日期、状态、延期和既有计划基准。
2. PATCH 单项及三项，验证 PLM 原页面刷新可见正式值，另外字段不变；三个 null 均按明确清空处理，建议值不补入。
3. 重放成功请求，历史只增加一次；同键改内容 409；尝试只读字段或非法日期，整次不保存。
4. 先 GET，再由 PLM 原页面修订，旧 ETag PATCH 必须 412；双方重新核对后提交，不能无条件覆盖。
5. 分别验证暂停、停用、删除限制；仅填计划不能变为交付完成，抵墨不得覆盖已验收状态。
6. 建立无模具新型号，null 编码仍可查；分码为 0185 后同一 ID 更新，CRM 保留前导零。
7. 两类全量期间持续创建/更新/删除，完成最初 S0 后补偿，再核对当前集合；失败扫描不得清空旧 CRM 数据。
8. 原系统办理停用/删除/恢复，CRM 顺序处理标记，不级联删除业务单据；410 后全量重建。
9. 用实际规模测批量导入、品牌/产品级改名/停用扇出、日切积压、同时操作及锁等待，记录 SQL/耗时/错误率。

## 7. 上线前尚需完成的验收

- CRM 实际域名/凭证/网络、反向代理、实际页面和两端业务联调。
- 生产规模数据、查询过滤扫描成本及每页关联查询成本评估；本版已批量复用排程投影，但未消除全部逐资源关联查询，不能宣称已压测优化。
- 所有正式维护/导入入口在开启触发器下的回归，尤其多行/多表事务与 CRM 并发的完整锁序矩阵。
- 多实例 Publisher、进程崩溃/恢复、长时间离线补偿及故障注入；目前已验证倒序提交和同键并发，但不冒充全面灾备验证。
- 既有专用数据库集成测试需在其合法隔离环境补跑；符号链接测试需在具备权限的 Windows 或 Linux 测试机补跑。
- 维护窗口初始化实际耗时、触发器扇出/死锁风险、发布/日切容量、告警及回退演练。

以上属于生产放行条件，不改变“本次代码及最终接口文档已交付、默认关闭、未擅自上线”的边界。

## 8. 2026-11-11 产品与 SKU 分离实施补充（v3.1）

本节为最新本地交付结果；前文 2026-11-09 的 46 项测试及未部署描述保留为历史记录，不代表当前本地状态。未操作生产环境。

### 8.1 效果、边界与调用链路

保持 `/api/integrations/v3/crm` 路径及原日期 PATCH 协议不变，在实物排程列表、详情和成功 PATCH 的新响应中增加 `productSchemaVersion: 2`、独立 `skus` 数组；原 `products` 收敛为产品线及非 SKU 型号项目。仍然是 CRM 查询/轮询，不是新增主动推送。

- 显式 sku 与历史颜色 SKU 分开输出；历史分类复用 PhoneProductEvidence，不按名称或 N 前缀猜测。来源 ID、编码、前导零原样保留，不更改 PLM 建档、共模、库存或编码生成规则。
- 仅输出原协作主档产品线及显式 item_project 关系。没有关系则返回空数组，不递归塞入产品线下全部 SKU，不将关联冒充为每套模具的已确认适配。
- 产品、SKU 类型/编码/颜色/自身型号/停用等字段修改后捕获相关排程变更；删除或移除关联会从最新数组消失。CRM 应用最新数组替换关联，不只追加。
- 聚合版本增加结构标识；新增迁移为当前已跟踪未删除的排程生成刷新捕获，保留 epoch、游标和历史幂等记录。旧成功请求幂等重放仍返回原存储响应。

查询链路：CRM → 8080 专用网关 → 原 CRM Controller/鉴权 → CrmQueryService → 原排程投影及显式产品关联 → products/skus 分组 → CRM 分别保存。

更新链路：原 PLM 维护入口 → plm_product/显式关联 → 原源变更捕获函数（扩展字段监听）→ capture → Publisher → `/changes` → CRM GET 最新详情并替换两组关联。

影响范围为 CRM 排程数据投影、版本和变更捕获，以及 CRM 侧消费映射；手机型号查询结构、原业务前端代码、日期写权限与状态规则、旧 v1/v2 接口均未改变。前端仍沿用已运行的模具台账版本。

### 8.2 源码修改文件

路径相对 `D:/work/PLM_new/DeepLinkPLM-main`。

| 文件 | 修改内容 |
| --- | --- |
| plm-server/src/main/java/com/yuewei/plm/module/integration/crm/openapi/CrmQueryService.java | 查询直接关联，拆分 products/skus，增加 SKU 来源字段及结构版本 |
| plm-server/src/main/java/com/yuewei/plm/module/integration/crm/openapi/CrmSyncStore.java | 只对 MOLD_SET 聚合 ETag 加 products-skus-v2 结构标识 |
| plm-server/src/main/resources/db/migration/V20261111_1000__crm_openapi_separate_products_skus.sql | 新增迁移，扩展原产品触发器字段及当前排程刷新；不修改旧迁移 |
| plm-server/src/test/java/com/yuewei/plm/module/integration/crm/openapi/CrmOpenApiUnitTest.java | 分组、字段、空值、原编码及前导零测试 |
| plm-server/src/test/java/com/yuewei/plm/module/integration/crm/openapi/CrmOpenApiPostgresTest.java | 显式/历史 SKU、关联范围、列表/详情/PATCH、属性修改、分类移动、删除及迁移刷新测试 |
| scripts/test_crm_openapi.ps1 | 隔离测试库追加执行新迁移 |
| docs/PLM-CRM-实物排程与手机型号最终接口文档-v3.0.md | v3.1 最终契约、字段表、示例、兼容说明及本地运行状态 |
| docs/PLM-CRM-实物排程与手机型号实施与测试报告-v3.0.md | 本节实施、部署和验证证据 |

### 8.3 已执行验证

2026-11-11 08:54 一键隔离测试：50 项，failures=0、errors=0、skipped=0，BUILD SUCCESS。分别为 CRM Unit 8、Security 3、真实 PostgreSQL 14、MoldScheduleService 9、MoldSchedulePolicy 12、MoldExecutionPolicy 3、SecurityConfig 1。未重新执行全部项目测试，不将历史全量回归的环境错误算作本次已通过。

本地部署后执行 `local-runtime/test-crm-readonly.ps1`：19 项全部通过。覆盖有效/无效/缺失凭证、手机分页、排程列表/详情、两数组类型和不重叠、列表详情一致、三个可写字段、ETag 前置条件、增量游标和网关隔离。只执行被前置条件拒绝的 PATCH，没有修改业务日期。

真实本地数据只读抽查（非接口文档中的虚构示例）：

| moldSetId | products | skus | 核验 |
| --- | --- | --- | --- |
| 564 | 1 个产品线 | 6 个历史 model_variant SKU | 历史颜色 SKU 已从产品集合移出 |
| 557 | 1 个产品线 | [] | 不凭父产品线扩大 SKU 关联 |
| 559 | 1 个产品线和 1 个型号项目 | [] | 非 SKU 的在研项目仍是产品信息 |

本机前端、局域网前端和健康接口均为 HTTP 200；CRM 入口仍为 `http://192.168.5.71:8080/api/integrations/v3/crm`，API Key 未变。尚未从另一台 CRM 电脑或 CRM 实际页面验收。

### 8.4 本地小增量部署及备份

运行目录 `D:/work/PLM_new/20261017-c856_bHNzr/local-runtime`。当前后端镜像 `yuewei-plm-server:local-crm-product-sku-20261111-r2`，以前一运行镜像为基础，仅替换 JAR 中两个 CRM class 并增加本次 SQL；逐文件哈希核验除目录条目外只有这 3 个文件变化。运行 JAR SHA-256：`6BD36117B537C0649236BB1EF292D7B16C62209FD9A978FE179FE252C27A5703`。

首次启动因旧包未携带已执行历史迁移 20261109.2100 而校验失败。核对源历史文件与数据库校验和均为 `1264642022` 后，将原文件补入镜像 `/app/legacy-migrations`，未编辑其内容、未执行 repair、未改写迁移历史。最终 124 项迁移验证成功，仅新执行 20261111.1000。

同步 ready/capture_enabled 均保持 true，部署前后 stream_epoch 相同；已发布变更覆盖当前 42 套排程，待发布捕获数为 0。未重新初始化同步、删除幂等记录或变更业务日期。

本地配置只更新基础 compose 的 server.image，沿用 CRM 私有配置；启动既有网关，以原前端镜像恢复已配置的双地址 8111 映射。未重启 PostgreSQL、Redis 或独立 8113 预览服务。后续使用 `start.cmd` 或 `start-crm.cmd`；不要用旧的额外覆盖文件把 server.image 覆盖回老版本。

备份/构建目录 `local-runtime/crm-product-sku-release-20261111/`：app.before.jar、compose.before.yaml、database.before.dump（146,914,493 字节，pg_restore 列表可读）、增量构建脚本、Dockerfile 和校验工具。该目录包含业务资料，已加入本地 .gitignore，不上传公开仓库。

代码回退应基于备份 JAR 构建镜像，继续携带校验通过的历史迁移文件及已应用的本次迁移，以保留 Flyway 校验能力；不能简单删除迁移或盲目使用缺少迁移文件的旧包，不自动恢复整库覆盖新业务数据。

### 8.5 CRM 接入与复测方法

1. 更新 CRM 解析：产品/项目读 products，SKU 读 skus；来源 ID 分别用 productId、skuId，SKU 编码读 skuCode，颜色读 color/colorCode。
2. 携带现有 Bearer Token GET `/mold-schedules/564`，确认 productSchemaVersion=2、products 和 skus 独立且无重复来源 ID；再 GET `/mold-schedules/557` 验证空数组。
3. CRM 从原有效游标消费 `/changes`，对刷新排程 GET 最新详情；按当前数组替换旧关联。原先放进产品集合的 SKU 要移除并归入 SKU，不能保留重复产品。
4. 在隔离测试环境用原维护入口修改直接关联 SKU 的颜色、编码、类型或启停，确认出现排程变更、ETag 变化、两数组更新；删除/解除关系后确认旧关联移除。不要随意修改真实本地业务档案做破坏性测试。
5. 日期回传继续使用详情的新 ETag 和新的 Idempotency-Key；按原文档三个日期协议验证，不改产品/SKU 只读字段。完整链路请在 CRM 实际页面补验。
