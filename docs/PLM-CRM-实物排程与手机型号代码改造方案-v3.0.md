# PLM–CRM 实物排程与手机型号代码改造方案

> 后续已按本方案实施。本文保留原始设计，不改写当时的授权边界；实际类/文件、维护窗口初始化等落地调整和测试结果见 [实施与测试报告](./PLM-CRM-实物排程与手机型号实施与测试报告-v3.0.md)，对外契约见 [最终接口文档](./PLM-CRM-实物排程与手机型号最终接口文档-v3.0.md)。源码完成不代表已部署上线。

| 项目 | 内容 |
| --- | --- |
| 版本 / 日期 | v3.0 / 2026-11-09 |
| 对应契约 | [实物排程与手机型号接口文档 v3.0](./PLM-CRM-实物排程与手机型号接口文档-v3.0.md) |
| 源码目录 | `D:/work/PLM_new/DeepLinkPLM-main`，不是服务器部署文件目录 |
| 文档性质 | 根据现有源码制定的实施设计，新增类名、表名和方法名均为拟定名称 |
| 本次实际变更 | 只新增本方案文档；未修改 Java、前端、SQL、配置或数据库，未部署或联调 |

> 本方案以已经确认的三日期回写和独立手机型号查询为准。旧 v1.0 推送方案、v2.0 申请维度方案及其实施计划不再适用。本文件中的代码为设计示意，不表示已创建相应文件或已完成测试。

## 1. 结论与实施原则

可实施，推荐采用：**独立 CRM 集成模块 + 原有保存逻辑的三日期专用分支 + 数据库事务变更捕获 + 顺序发布的增量查询**。

“小增量”具体落实为：

1. 新 API 使用 `/api/integrations/v3/crm/**`，不修改任何旧 Controller 的路径、参数、响应格式。
2. 查询复用现有数据和状态规则，只新增对外 DTO、字段投影及分页查询，不把整个内部数据库行暴露给 CRM。
3. 写入只新增一个明确的三日期内部入口，复用现有字段校验、执行约束、历史记录和正式保存，不复制一套排程业务。
4. 独立安全链只保护新命名空间，不扩大老接口匿名范围，也不将 CRM 凭证当成管理员登录凭证。
5. 优先用新增迁移中的事务触发分支覆盖多个正式写入入口，避免在型号、编码、申请、导入、用户等十几个 Service 里逐处添加网络同步调用。
6. 不修改老字段含义，不覆盖历史编码，不修改计划基准，不要求前端改造，不启用旧推送。
7. 完整上线必须包含鉴权、并发保护、幂等、删除/停用通知；不能把只完成查询称为完整实现 v3.0。

需明确：新增触发器虽然减少 Java 修改面，仍会进入旧写入事务，有锁竞争、故障传播和性能成本。必须先在隔离数据库验证；若依赖覆盖或锁顺序验证不通过，暂停上线该方案，而不是悄悄退化为可能漏数据的同步。

## 2. 已确认业务范围与预期效果

### 2.1 数据方向

| 内容 | 规则 | 预期效果 |
| --- | --- | --- |
| 当前实物排程及产品/型号/模具编码 | PLM → CRM，全部产品范围，只读 | CRM 分页查询并展示与 PLM 一致的当前数据 |
| 实际抵墨 `actualMexicoArrivalDate` | CRM → PLM | 保存实际日期，重新计算相应进度 |
| 计划验收 `plannedAcceptanceDate` | CRM → PLM | 保存确认计划，不自动标记验收完成 |
| 计划试模量产 `plannedProductionDate` | CRM → PLM | 保存确认计划，不自动标记实际量产完成 |
| 三日期清空 | 显式 null，未出现字段不变 | 只清空指定项，不覆盖其他日期、不采用建议值 |
| PLM 人工修改三日期 | 保留现有权限和校验 | CRM 下次查询/增量可读到人工修订，不无条件覆盖 |
| 独立手机型号和型号编码 | PLM → CRM，型号主数据只读 | 没有申请、模具的新型号也可查询；编码后补时更新同一 ID |
| 删除、停用、恢复 | 增量 DELETE、DISABLE、ENABLE | CRM 停止或恢复使用，不级联删除 CRM 业务单据 |
| 历史数据 | 不提供旧版本/操作历史；首次读取当前有效数据 | 当前仍有效的老记录正常同步，接入前已删除档案不迁移 |

一条排程对应一套实物，目标键为 `moldSetId`。同型号多套不合并；PC/TPU 部件、多个适用型号或多张申请不重复生成实物记录。未建档申请不在实物列表中，相关新型号由独立接口提供。

### 2.2 非目标

本期不开发 CRM 主动推送接收端、Webhook、SKU 全量对接、完整申请表单传输、附件/费用传输、CRM 修改主数据、CRM 修改任意状态、页面改版、历史重放及运行服务切换。

“全部”仅表示这两类业务对象的全部产品范围，不表示 CRM 获得全系统管理权限。正式停用不等于执行暂停；交付完成也不等于停用。

## 3. 源码核对结果及复用边界

本节文件均为已存在文件，路径相对源码根目录。

| 现有文件 | 已核实能力 | 本版处理 |
| --- | --- | --- |
| `plm-server/src/main/java/com/yuewei/plm/module/collaboration/MoldScheduleController.java` | 排程列表/详情、申请列表、批量保存等内部 API | 不改 Controller、不把 batch-save 直接开放给 CRM |
| `plm-server/src/main/java/com/yuewei/plm/module/collaboration/MoldScheduleService.java` | `page`、`projectRows`、`detail`、`save`；私有 `enrichPlans` 统一补齐排程、型号、任务和基准信息 | 活跃记录查询复用 `projectRows`/`detail`；不扩大私有方法可见范围，不改旧返回值 |
| `plm-server/src/main/java/com/yuewei/plm/module/collaboration/CollaborationService.java` | `save` 逐行事务，`saveRow` 私有方法统一锁定、旧值校验、日期校验、写入、历史和审计 | 新增三日期单行入口及锁后前置条件分支；保留原批量部分成功语义 |
| `plm-server/src/main/java/com/yuewei/plm/module/collaboration/CollaborationRepository.java` | 套、PC/TPU、产品/型号关联、实物编号及交付状态 SQL | 新 Repository 复用公开能力和既有关系口径；旧 SQL 默认筛选不改 |
| `plm-server/src/main/java/com/yuewei/plm/module/collaboration/MoldScheduleTypes.java` | 三日期字段定义、计划/预测/实际区分、实际日期修订原因及量产前置校验 | 直接复用，不更改规则 |
| `plm-server/src/main/java/com/yuewei/plm/module/collaboration/MoldSchedulePolicy.java` | 上海业务日期、待办完成条件和逾期计算 | 直接复用；另加日期推进通知任务，不改原规则 |
| `plm-server/src/main/java/com/yuewei/plm/module/collaboration/MoldWorkflowRepository.java` | `lockExecution`、暂停判断、来源申请与待开工投影 | 写前复用执行锁；查询保留暂停/待开工语义 |
| `plm-server/src/main/java/com/yuewei/plm/module/moldreference/PhoneModelReferenceRepository.java` | 标准机型、品牌、编码、启用/删除状态和版本 | 独立型号查询的数据依据，不以模具为查询前提 |
| `plm-server/src/main/java/com/yuewei/plm/module/moldreference/PhoneModelReferenceService.java` | 型号维护、品牌启停、编码关联和适配规则 | 原 API 不改；由数据库捕获其正式变更 |
| `plm-server/src/main/java/com/yuewei/plm/module/masterdata/ProductMoldLifecycleService.java` | 产品/主档/实物的停用、删除、启用及祖先有效性检查 | 复用有效性约束，不新增“绕过停用”的写法 |
| `plm-server/src/main/java/com/yuewei/plm/common/config/SecurityConfig.java` | 旧安全链主要管 `/api/v1/**`，其余路径最终 permitAll | 不依赖该默认规则，新增高优先级、专用路径安全链 |
| `plm-server/src/main/java/com/yuewei/plm/common/security/SimpleTokenAuthenticationFilter.java` | Bearer 被解释为普通登录会话，且全局安全开关可跳过 | CRM 独立链不装入该过滤器，不修改其全局白名单 |
| `plm-server/src/main/java/com/yuewei/plm/module/pilot/PilotWebConfig.java` | 试点拦截器只注册 `/api/v1/**` | 不改拦截器；新链和新服务自己校验 scope |
| `plm-server/src/main/java/com/yuewei/plm/module/pilot/PilotAccessService.java` | 有真实账号、最小权限列表及 `all_objects` 范围机制 | 采用独立非管理员服务账号，配置全部业务对象范围，不改权限解析规则 |
| `plm-server/src/main/java/com/yuewei/plm/module/integration/crm/moldschedule/service/MoldScheduleCrmSyncService.java` | 旧完整快照推送、旧状态表及重试机制 | 保持原状，不复用旧版本/Outbox 表承载 v3 游标 |
| `plm-web/src/views/schedule/MoldScheduleView.vue` | 截图表格及暂停、待开工优先展示 | 不改前端；作为接口展示一致性的验收依据 |
| `plm-web/src/utils/moldPresentation.ts` | 本套适用手机、来源申请及适配待确认文案 | 新投影按同一依据表达，不把待确认关系变成已确认 |

两个关键结论：一是旧 `save` 会把异常转换为逐行结果，不能直接套给新接口后忽略事务回滚；二是只盯住模具套自身 `updated_at` 会漏掉型号、品牌、负责人、申请执行及关联关系变化。

## 4. 推荐模块结构与新增文件

以下均为**计划新增**，本次不创建代码文件。新增模块根目录为 `plm-server/src/main/java/com/yuewei/plm/module/integration/crm/openapi/`，避免与旧 `moldschedule` 推送包混用。

### 4.1 Controller、DTO、安全和异常

| 拟新增相对文件 | 主要代码职责 | 预期效果 |
| --- | --- | --- |
| `controller/CrmMoldScheduleController.java` | 新增列表、详情、三日期 PATCH 三个路由 | 不碰旧排程 Controller |
| `controller/CrmPhoneModelController.java` | 新增型号列表、详情 | 无模具的新型号可查 |
| `controller/CrmChangeController.java` | 统一两类实体的 changes 查询 | 删除/停用/恢复可被 CRM 获取 |
| `dto/CrmOpenApiTypes.java` | 查询参数、分页、排程、型号、日期补丁、变更、错误等 record/DTO | 字段白名单、ID 字符串化、统一对外格式 |
| `config/CrmOpenApiProperties.java` | enabled、write-enabled、主体、凭证摘要、保留期、页大小等校验 | 默认关闭、空凭证拒绝启动或拒绝开放 |
| `config/CrmOpenApiSecurityConfig.java` | 独立 `@Order(1)` SecurityFilterChain，仅匹配新命名空间 | 即使旧全局安全关闭，新 API 仍不能匿名访问 |
| `security/CrmServiceAuthenticationFilter.java` | 只校验 CRM 服务 Bearer；建立 scopes 和真实服务账号上下文，finally 清理 | 不把服务凭证用于老 API，不冒充管理员 |
| `security/CrmServicePrincipal.java` | 稳定 subjectId、scopes、serviceUserId、配置世代 | 凭证轮换不改变幂等归属，scope 变更可使令牌失效 |
| `web/CrmOpenApiExceptionHandler.java` | 仅作用于新 Controller，映射 HTTP 400/401/403/409/410/412/422/428 等 | 不改老接口的 ResponseVO 和全局异常处理 |

新过滤器不能被 Spring Boot 额外注册为全局 Servlet Filter，应通过禁用 FilterRegistrationBean 或仅在安全配置内实例化，确保只运行一次、只作用于新路径。未定义的新路径/方法默认拒绝；无效普通用户 Token 也不能访问 CRM 专用接口。

补丁解析须在新 DTO/专用解析器内保留“未提交”和“显式 null”的区别，并拒绝重复 JSON 键及未知属性；不能修改全局 ObjectMapper 的反序列化选项而改变老接口。推荐 JsonNode 加逐层字段白名单，再转换成强类型日期补丁。

### 4.2 查询、保存和同步组件

| 拟新增相对文件 | 主要代码职责 | 预期效果 |
| --- | --- | --- |
| `repository/CrmProjectionRepository.java` | keyset ID 分页、跨产品精确筛选、独立手机查询、停用对象只读查询、关联反查 | 不修改老 offset 分页及默认范围 |
| `service/CrmProjectionAssembler.java` | 白名单 DTO 投影、编码校验、关系确认标记和有效状态归一化 | 排除费用、附件及整份开发数据 |
| `service/CrmScheduleQueryService.java` | 当前列表/详情一致性读取，调用原排程 enrichment | 同一快照返回正式值及版本 |
| `service/CrmPhoneModelQueryService.java` | 型号当前投影、编码为空处理 | 标准空码不回退历史码 |
| `service/CrmScheduleDateService.java` | scope、显式 null、幂等、事务、锁后版本校验及新保存桥接 | 三日期原子更新、清空、返回正式数据 |
| `service/CrmResourceStateService.java` | 聚合版本、依赖状态读取、锁后校验、数据库捕获 readiness 检查 | 不混用旧主档/申请/推送版本 |
| `service/CrmIdempotencyService.java` | 请求摘要、唯一键竞争、成功结果同事务持久化和重放 | 超时重试不重复保存 |
| `service/CrmChangeService.java` | 固定发布上界、全局 changes 分页及过期判断 | 两类资源共享一个可靠游标 |
| `service/CrmChangePublisher.java` | 已提交捕获记录 → 短事务顺序发布 | 不以原始自增 ID 作为无遗漏水位 |
| `service/CrmProjectionReconcileJob.java` | 日期推进、漏项核对、过期日志/幂等清理及健康指标 | 自然日导致的延期变化可同步，离线恢复可核对 |
| `support/CrmCursorCodec.java` | 分页令牌和同步游标签名、有效期、主体/筛选/世代绑定 | 客户端不能篡改边界跳过数据 |
| `support/CrmRecordVersionCodec.java` | 实体 ID、聚合修订、业务日期/权限世代的对外不透明版本 | 版本不能跨对象或账号误用 |

类可在实现时合理合并，但查询、写入、安全、同步事务边界不能混在一个大 Controller 里。没有必要新增第三方消息中间件、Redis 强依赖或网络推送。

## 5. 现有文件的精确修改计划

### 5.1 必改的业务 Java：仅先限定一个文件

**文件：`plm-server/src/main/java/com/yuewei/plm/module/collaboration/CollaborationService.java`。**

新增以下内部能力，不增加旧 Controller 路由：

1. 新增 `saveCrmScheduleDates(...)`，只接受 moldSetId 和三个日期，必须已经存在写事务；检查真实服务账号、现有 schedule 写权限、正式有效性及三日期白名单。
2. 新增锁后前置条件类型 `LockedRowPrecondition`（可用本类内嵌接口），传入新模块的版本/幂等相关检查，不接受客户端可控的“跳过校验”布尔值。
3. 原私有 `saveRow(actor, allowed, patch, request)` 保留，委托新增重载，默认使用无额外条件的老分支。新重载在 `workflow.lockExecution` 和原记录锁完成、读到 current 后执行前置条件，再进入原校验和写入流程。
4. 新单行入口在相同事务内取得执行/实物锁和当前值，再构建 CellChange 的 before/after；不得使用 CRM 伪造的 before，不得在无锁预读后直接覆盖。
5. 仍使用原组权限、日期规范化、暂停约束、validateParts、MoldScheduleTypes.validate、history、log 及正式 SQL，不复制它们。
6. 新入口直接调用单行保存核心并向上抛出错误，不经旧 `save(BatchSave)` 的异常转逐行结果包装。非 saved 结果必须转换异常，使外层事务回滚。旧 save 的逐行事务和部分成功规则不变。
7. 如果三个提交值均未变化，走新入口的 no-op 分支，不调用老核心的无条件 master_data_version 自增；保留本次成功幂等结果。

设计示意，非可直接粘贴的完整代码：

```java
// 保留旧入口及原有逐行结果语义。
private RowResult saveRow(CurrentUser actor, Set<String> groups,
                         RowPatch patch, HttpServletRequest request) {
    return saveRow(actor, groups, patch, request, LockedRowPrecondition.NONE);
}

// 新重载：原来锁定/读取 current 的位置之后，只增加一个前置条件分支。
// workflow.lockExecution(...) -> lock(...) -> current = repository.row(...)
// precondition.verify(current);   // CRM 调用时检查 lifecycle、If-Match 等
// 后续继续执行原校验、保存、history 和 log，不另写直接 UPDATE。

// 新三日期入口只供集成适配调用：验证 scope/账号/白名单，
// 加锁取 before，使用原核心；失败抛出异常，加入外层事务。
```

实现前以单测固定原 saveRow 行为，再添加重载，避免为了新分支顺带重构整个 CollaborationService。不得把 saveRow 改成不受限制的公开任意字段入口。

### 5.2 必改配置及拟新增迁移

| 文件 | 动作 | 变更内容 |
| --- | --- | --- |
| `plm-server/src/main/resources/application.yml` | 小范围新增配置块 | 新增 `plm.integrations.crm.openapi`，默认 enabled=false、write-enabled=false；保留旧 mold-schedule 配置不动，敏感值仅外部注入 |
| `plm-server/src/main/resources/db/migration/V20261109_1900__crm_openapi_sync_foundation.sql` | 拟新增 | 新建同步状态、聚合版本、捕获队列、发布日志和幂等表；不修改已执行迁移，不删除原表 |
| `plm-server/src/main/resources/db/migration/V20261109_1910__crm_openapi_source_capture.sql` | 拟新增 | 安装默认关闭的事务捕获函数/触发器及必要索引；覆盖第 8 节依赖表 |

迁移文件名为建议占位；实施时重新检查现有最大版本及其他任务的新增迁移，避免编号冲突。配置只描述开关和环境变量名，不提交真实 Token、摘要对应的明文、数据库密码或用户会话。

### 5.3 明确保留原状的文件/模块

- 旧 `SecurityConfig`、`SimpleTokenAuthenticationFilter`、`PilotSecurityInterceptor` 和全局异常响应：通过新增专用安全链隔离，原则上不修改。
- `MoldScheduleController`、`MoldScheduleService`、`CollaborationRepository`、`MoldScheduleTypes`、`MoldSchedulePolicy`、`MoldWorkflowRepository`：按原公开能力复用，不直接变更签名或默认查询。
- 手机维护、编码维护、申请转换、导入、主档生命周期、用户维护 Service：优先不添加逐处 Java 同步调用，改由事务捕获覆盖。
- `plm-web/**`：无需改动，CRM 回写保存正式数据后，PLM 页面刷新即可读取。
- `integration/crm/moldschedule/**` 旧推送代码及其 SQL：不删除、不启用、不把旧 Outbox 改成新游标表。

若后续验证发现某个投影缺少可复用公开能力，只新增窄范围方法，并在本方案补充具体文件、理由和回归项；不得默默扩大为修改上述所有模块。

## 6. 字段映射与查询实现

| 对外字段 | 数据依据/复用逻辑 | 注意事项 |
| --- | --- | --- |
| moldSetId、itemId、assetNumber、setNo | `plm_collaboration_mold_set`、主档及 `CollaborationRepository.assetSql()` | ID 字符串化；编号只是展示 |
| styleName、products | 当前 productLineName/styleName 展示规则、正式产品及项目关联 | 只读，不通过自由文本猜产品编码 |
| compatiblePhones、展示文字 | 套级兼容表、主档手机成员、来源申请标准机型、`moldPresentation` 语义 | 区分 CONFIRMED、PENDING_CONFIRMATION、APPLICATION_SOURCE |
| owner | 实物套 owner_user_id → sys_user.display_name | 只返回所需 ID/展示名 |
| openedDate、plannedMoldDeliveryDate、actualShipDate、plannedFactoryArrivalDate | 实物套对应正式列 | CRM 只读，计划/实际不混用 |
| actualMexicoArrivalDate | `plm_collaboration_mold_set.actual_mexico_arrival_date` | 允许清空，非到港/到厂 |
| plannedAcceptanceDate | `plm_collaboration_mold_set.planned_acceptance_date` | 允许清空，不是验收结果 |
| plannedProductionDate | `plm_collaboration_mold_set.planned_production_date` | 允许清空，不是实际量产 |
| deliveryStatus | 原 moldStatusSql、MoldScheduleTypes.enrich、workflow 标志 | paused/planned 仅对外归一化；优先级与页面一致 |
| overdue、overdueTasks | MoldSchedulePolicy 与上海业务日期 | 不用建议日期替代已保存计划来判逾期 |
| moldParts.moldCode | 部件关联的正式目录码及原刻码，按现有优先级读取 | 完整码校验后返回；历史五位目录前缀不冒充完整码 |
| phoneModelCode | 标准机型关联 plm_code_item 的当前有效标准码 | 保留四位前导零；未分配/编码不可用返回 null，不拿历史申请码顶替 |

正常模具查询先用新增 Repository 做 keyset ID 筛选，再批量调用原 `projectRows` 进行 enrichment，最后投影 DTO。手机型号独立从标准主数据读取，不 join 模具作存在性条件。

当前停用对象查询不能简单套用老方法的 active/inner join 假设。新增 Repository 对已停用但未删除的目标提供只读路径，并保留被停用/删除祖先的必要引用信息，使用适当 LEFT JOIN；不修改老页面默认 SQL。已物理删除的目标不重建，仅靠 tombstone 标记通知。停用详情也不得泄露未约定字段。

详情及分页单次数据库读取采用只读 REPEATABLE READ 事务，使正式数据、依赖和聚合修订来自同一快照；分页跨请求仍为实时读取，靠首次起点及增量补偿，不宣称多页全局快照。

## 7. 鉴权、事务、并发和幂等

### 7.1 独立服务身份

新增专用链以 securityMatcher 限定 `/api/integrations/v3/crm/**`；旧链继续处理旧路由，普通登录和全局安全开关不影响新链的强制认证。

部署时建立独立真实服务账号：`all_permissions=false`，对应 `plm_pilot_account` 配置最小内部读/写权限、`all_objects=true`，字段职责仅满足 logistics/mexico 所需；不要配置 finance/engineering 或管理员权限来省事。实际配置须验证兼容现有权限检查，并禁止将其作为人工共享登录账号。

Bearer 的高熵随机秘密只由安全渠道交付；配置保存摘要/安全引用，比较使用恒定时间方式，支持轮换和撤销。subjectId 独立于 Token 值；轮换后同主体幂等记录仍可用。过滤器从数据库重新确认服务账号仍在用，建立受控 CurrentUserContext 供原保存审计使用，最后恢复/清理线程上下文。

新增 scope 三项：`crm:mold-schedule:read`、`crm:phone-model:read`、`crm:schedule-dates:write`。内部字段组权限不能替代外部 scope，也不能把普通用户 Token 解释为 CRM 服务凭证。CRM 凭证调用旧 batch-save、型号维护和管理接口必须失败。

### 7.2 日期 PATCH 的完整事务

推荐 TransactionTemplate 为新写入单独建立 REPEATABLE READ 事务，避免修改全局事务隔离级别。顺序为：

```text
认证/feature gate/scope/JSON 白名单校验
  → 当前主体 + Idempotency-Key 的互斥和成功结果检查
  → 原有执行锁 + 实物记录锁
  → 锁后 lifecycle/权限/聚合修订检查（含 If-Match）
  → 使用锁内 current 构建三日期 CellChange
  → 原 saveRow 核心：旧值、日期、执行、部件、计划约束
  → 正式数据、原审计、事务捕获标记
  → 同事务读取保存后 DTO/新 ETag，并保存幂等成功结果
  → 提交；任何失败均回滚
```

新入口加入外层事务，不使用 REQUIRES_NEW 单独提交业务，不经 HTTP 自调用旧接口，不在事务中请求 CRM。成功响应必须在提交成功后才发出。

### 7.3 聚合版本

新增资源状态中的 revision 在源业务事务内按受影响实体原子递增；不复用 master_data_version、申请 version、planVersion 或旧推送 recordVersion。对外版本封装 entityType、ID、revision、模具状态计算日及必要权限/配置世代，签名或严格编码成不透明字符串。

CRM 写入在原执行锁/实物锁之后锁定资源 revision 行并检查 If-Match，再执行旧保存核心。所有被纳入依赖的源修改都必须在同事务修订该资源版本；未覆盖依赖不得宣称完整并发保护。REPEATABLE READ 遇到快照后资源修订提交，应按并发失败处理，不可继续使用旧快照写入。

锁顺序以现有执行/实物和主档生命周期锁为基准，资源 revision 是新增锁，依赖扇出按 `(entityType, id)` 排序。实施时必须针对“型号维护、部件更新、主档停用、申请暂停”与 PATCH 建立锁顺序测试。触发器只访问捕获/版本元数据，不反向更新源业务行。若仍出现死锁/序列化失败，新集成请求可在全事务回滚后有限重试（例如最多 2 次），每次重新检查原版本和幂等键；不能无限重试或替换为新业务版本。

新路径最终确认版本冲突返回 412，暂时性数据库争用返回可重试错误；失败事务不缓存成功结果。老请求错误语义不被新异常处理器接管，但触发器引入的锁争用必须通过回归证明可接受，不能只测 CRM 单线程。

### 7.4 幂等细节

唯一键 `(subject_id, idempotency_key)`，摘要包含 HTTP 方法、规范化目标路径、If-Match、规范化 JSON（包括字段是否缺省/null）。对象键顺序不同不算不同内容，重复键/未知字段提前拒绝。

认证有效后先查成功幂等结果，再做新请求版本检查。同键同内容返回原响应和 ETag；不同内容 409。成功记录与业务写入原子提交；并发插入竞争、隔离快照导致暂不可见时，回滚并在新事务重查，不能返回空的“成功”。不存在永久卡住的独立 processing 提交。

保留期至少 7 天；响应副本只保留本接口必要字段，按敏感业务数据保护。用户权限或凭证已被撤销时不得因缓存命中绕过认证。超时后 CRM 以原键和原内容重试；幂等重放数据可能旧于当前详情，需要当前值时再查询。

## 8. 不漏入口的事务变更捕获

### 8.1 为何不逐处改旧 Service

现有保存并不都经过 MoldScheduleService。旧系统已有排程导入、主档变更、标准型号、编码、申请建档及人工修订等多条链路。只在新 PATCH 或旧一个保存方法加通知会漏掉 PLM 自身变化。

推荐在新增迁移中为明确源表安装轻量事务触发器：检测对外字段或依赖列的实质变化，计算受影响对象 ID，递增资源 revision，并向新捕获队列追加最小标记。捕获记录随源事务提交或回滚，不作 HTTP 调用，不构建大 JSON，不在原写入事务中运行全量排程 enrichment。

默认 capture_enabled=false，关闭时立即返回；部署后先验证捕获完整性，再开放新 API。启用期间捕获失败必须导致同事务回滚，不能吞掉异常后留下无法追踪的业务修改；这是需评估的旧业务影响。

### 8.2 依赖覆盖清单

下列均为现有表；按实际列白名单触发，不监听无关费用/备注就制造大量同步。

| 源表 | 需要捕获的变化 | 受影响对外对象 |
| --- | --- | --- |
| `plm_collaboration_mold_set` | 三日期、其他表格日期、owner、套次、状态依赖、item 关联、生命周期、删除 | 本套 MOLD_SET；搬移关联覆盖旧/新主档 |
| `plm_collaboration_mold_part` | 编码、到厂、验收、部件状态/位置等对外进度依赖、删除 | 所属旧/新实物套 |
| `plm_collaboration_item` | 款式、产品、适用型号历史文字、主档有效性/删除 | 该主档下所有相关套 |
| `plm_collaboration_item_project` | 已确认产品/项目关联增删改 | 对应主档下所有相关套 |
| `plm_collaboration_phone_member` | 型号成员和套集合关联增删改 | OLD/NEW 关联套并集，不能只查删除后的新集合 |
| `plm_collaboration_phone_set_compatibility` | 本套适配及 PC/TPU 确认依据增删改 | OLD/NEW 对应实物套 |
| `plm_mold_application` | moldSetId、phoneModelId、来源、执行/建档状态、暂停/终止、历史关联标志及删除 | 来源展示或执行权限受影响的旧/新实物套，不发布申请实体 |
| `plm_phone_model_member` | 新建、标准名称/品牌/编码关联、启停/删除 | PHONE_MODEL，及引用该型号的所有相关套 |
| `plm_phone_brand` | 标准名称、启停、删除 | 该品牌型号及引用它们的相关套 |
| `plm_code_item` | 标准编码值/启停/删除 | 引用该码的型号及其相关套；只处理有关编码类型 |
| `plm_product` | 产品名称/编码、归属及有效性/删除 | 通过正式主档/项目关系引用的套；祖先变更反查全部受影响后代 |
| `plm_product_mold_code` | 完整目录码及有效性/删除 | 部件明确引用的实物套，不按名称关联 |
| `sys_user` | 负责人 display_name，及影响可显示性的正式删除/状态变更 | owner_user_id 引用的套；不捕获密码等敏感列 |

以上是本版投影依赖范围。若实现中使用了额外表或字段（如新增展示基准状态），必须先补齐依赖图、捕获及测试，不能在查询里加字段却不维护版本。服务账号权限/字段组配置变化通过认证重读、权限世代和令牌失效处理，不靠增加全系统用户敏感数据事件解决。

### 8.3 触发器与删除/扇出要求

- 使用 OLD/NEW 收集变更前后关联；物理删除必须在关联消失之前保留必要 ID。子表删除不能在删除后反查不到旧套就跳过。
- 父档的生命周期传播必须依据完整祖先有效性计算，不因一个父档重新启用就误启用仍被另一祖先停用的对象。
- 目标实体本身删除产生 DELETE；目标仍在但正式父档不可用，按生命周期映射产生 INACTIVE/DISABLE。暂停是独立业务状态，不能产生 DISABLE。
- 型号删除不能级联删除相关模具；更新兼容引用的可用性并通知模具投影变化。历史展示证据不能伪装成当前标准型号。
- 仅在投影依赖实质变化时修订版本；单次事务重复更新同一对象可多次修订，值相同则不制造无意义变更。
- 捕获函数先锁定/修订受影响 resource，再分配并追加 capture；不能先取 capture 序号、后等待资源锁。生命周期按锁后可见的正式依赖重新判断，避免等待前计算的旧结果覆盖新结果。
- 所有受影响资源必须完整覆盖；大品牌/产品扇出不得用 LIMIT 静默截断。批量场景优先使用 statement transition tables/去重集合，避免逐行 N×M 扫描，并对映射列建立索引。
- 不在触发器里保存 Token、用户密码、完整源行或旧业务快照。函数限定 schema/search_path，不允许任意客户端传表名执行动态 SQL。
- 核对任务作为捕获缺口告警和补偿，不把“等每日全量扫一次”当成正常增量保障；若发现 revision 漏更新，属于并发保护缺陷，须关闭写入口修复。

## 9. 新增数据表与发布顺序

以下结构为拟定字段清单，不在本次执行 SQL。全部使用新前缀 `plm_crm_openapi_`，不改旧推送表，不覆盖原业务数据。

| 拟新增表 | 关键字段/约束 | 用途 |
| --- | --- | --- |
| `plm_crm_openapi_control` | 单行 capture_enabled、ready、stream_epoch、published_head、retained_after、last_business_date | 开关、readiness、发布水位、保留边界及日期补偿 |
| `plm_crm_openapi_resource` | 主键(entity_type, entity_id)，revision>0、lifecycle、projection_updated_at | 事务级聚合版本与删除后的最小身份；不对业务表加级联外键 |
| `plm_crm_openapi_capture` | capture_id、stream_epoch、entity_type、entity_id、revision、action/lifecycle、captured_at、published_at/处理状态 | 源事务内捕获，原始 capture_id 仅定位，不作对外高水位 |
| `plm_crm_openapi_change` | change_id 主键、entity_type、entity_id、action、lifecycle、changed_at、capture_id 唯一 | 对外顺序增量标记，不存整行历史快照 |
| `plm_crm_openapi_idempotency` | 主键(subject_id, request_key)，request_hash、status、response_json、etag、created_at、expires_at | 单次成功结果重放，与业务事务同提交 |

必要索引包括捕获 pending、发布保留期、幂等 expires_at 以及源表反查列。resource 保留已删除对象的最小身份，不因清理 30 天变更日志同时删除仍用于版本/生命周期判断的记录；其最终清理单独设计并避开可用游标和引用。

### 9.1 两阶段：业务捕获与顺序发布

不能直接 `change_id=nextval()` 后按最大值取增量，因为事务 A 先取小号但后提交，事务 B 先提交大号，CRM 推进游标后可能永远漏 A。

推荐：

1. 源业务事务只插 capture，并同事务更新 resource。回滚则两者都不可见。
2. 独立 Publisher 在短事务中先锁 control 的发布头，再取一批**已提交、尚未发布**的 capture，按确定顺序处理，不根据“已处理最大 capture_id”跳过记录。
3. 锁定发布头期间分配连续 change_id，插 change、标记对应 capture 已发布、更新 published_head，一并提交。
4. 第二个发布实例必须等头锁；不能先分配 change_id 再释放锁、之后才提交。这样对外 change_id 的可见水位才不会越过未提交发布批次。
5. Publisher 崩溃则本批回滚，原 capture 仍待处理；capture_id 唯一约束防止重复发布。使用 pending 查询，即使较小 capture_id 晚提交，下一批仍会处理。
6. 同一实体的 resource revision 在源事务中串行维护；Publisher 不能把同实体较旧变更排在较新变更之后。批处理及重复捕获场景须验证 revision 顺序，不能用 SKIP LOCKED 跳过一个实体的旧事件而先发布其新事件。

对外顺序是已提交事实的可靠发布顺序，不承诺两种独立实体严格按数据库物理提交时刻排序。changedAt 是记录时间，CRM 只用游标推进，不用时间戳替代 change_id。Publisher 延迟需监控，否则 60 秒轮询本身不能保证可见时效。

### 9.2 全量起点、游标及过期

首次起点 S0 取当前已提交 published_head；即使存在尚未发布的源变化，其 capture 后续也会获大于 S0 的 change_id，不漏补偿。固定两类实体各自扫描最大 ID，按数值 ID 升序 keyset 分页；首个模具起点作为两类扫描后的统一补偿起点。

游标签名绑定 subject、stream_epoch、lastChangeId、本轮 upperBound、有效期；分页令牌另绑定实体、全部筛选、页大小、扫描上界和最后 ID。字符串 ID 不按字典序分页。

保留期拟定 change 30 天、幂等至少 7 天、pageToken 24 小时。清理只能推进已发布前缀的 retained_after；不能把未发布 capture 或待重试记录按创建时间直接删除。游标早于保留边界或 stream_epoch 不符返回 410，禁止自动跳到最新。

暂停捕获、改变依赖规则、重新初始化等会破坏连续性的操作必须提升 stream_epoch，使旧游标失效，并要求 CRM 完整当前数据重扫和补偿。扫描未全部完成时，CRM 不清空旧副本；成功后仅撤销未重现旧记录的可用性，不删除订单等业务历史。

切换世代应在受控维护流程中等待旧捕获事务结束后初始化；capture 携带世代，Publisher 不把旧世代积压事件混入新流。不得在线直接清空日志、重置发布头后继续宣称游标连续。

## 10. 生命周期、日期推进与初始化

### 10.1 生命周期映射必须落表

| 对象 | ACTIVE | INACTIVE | DELETED |
| --- | --- | --- | --- |
| 模具套 | 本套及正式有效性依赖未删除/停用 | 本套 master_data_status 非 active，或按既有规则祖先不可用；保留当前对象只读 | 本套 deleted_flag 或物理删除 |
| 手机型号 | 型号及品牌正式启用、未删除；允许尚未分码 | 型号/品牌正式停用或祖先规则导致不可用 | 型号 deleted_flag 或物理删除 |

源型号/品牌通常使用 enabled/disabled，主档使用 active/inactive，不能直接对外透传混用。编码被停用/删除时，型号不凭“编码为空”自动停用：保留型号身份，phoneModelCode 按已确认的有效标准码规则置 null，并发布型号及相关模具 UPSERT；品牌本身删除造成型号不可用时按父档有效性处理。具体源列在 migration 编写时以真实 schema 校验，不靠不存在的统一 status 列。

旧系统并非所有删除都允许恢复，ENABLE 仅在现有业务正式允许重新启用/恢复且目标重新可用时产生；本方案不新增业务恢复接口。

### 10.2 日期推进

新增 Job 使用 Asia/Shanghai 检查业务日，记录已处理日并可在重启后补偿。由于对外 asOfDate 也变化，保守方案为每个新业务日分批修订当前模具资源并记录 UPSERT，不修改源业务日期和进度事实。型号不因换日无故更新。

同一日重跑应幂等；大量记录分批并持久化进度，不能把“已处理日期”先写完后丢失后续批次。可后续优化为投影差异计算，但需同时处理 asOfDate 及版本语义，不以浏览器时区自行判逾期。

### 10.3 初始化顺序

1. 新迁移建表/安装关闭状态的捕获分支，不回填旧业务字段。
2. 开启捕获但保持外部读写关闭，建立新的 stream_epoch，先保证新变化有据可查。
3. 分批为当前对象初始化 resource，INSERT ... ON CONFLICT DO NOTHING；不能用种子版本覆盖捕获已生成的新修订。当前停用对象也建立状态，不回填已删除历史快照。
4. 建立当前日期处理状态、启动 Publisher，核对源对象与 resource 一致、依赖覆盖通过、健康检查 ready。
5. 配置真实服务账号及凭证，先开放查询，再用授权测试记录验证 PATCH，最后开放生产写入。
6. 初次 CRM 只读当前数据及 S0 之后变化，不把初始化当成业务“新增”，不回放接入前历史。

## 11. 调用链路

### 11.1 模具排程/型号查询

```text
CRM GET
 → 新安全链：服务凭证 + scope + 功能开关/readiness
 → 新 Controller / QueryService（只读一致性事务）
 → CrmProjectionRepository：keyset/精确条件/当前生命周期
 → 原排程 projectRows/detail 及原状态/待办逻辑，或独立标准型号查询
 → CrmProjectionAssembler：白名单字段、字符串 ID、关系提示
 → 同快照 resource 修订 → ETag / recordVersion / 分页令牌
 → CRM 展示当前正式数据
```

### 11.2 CRM 三日期回写

```text
CRM GET 取得最新 moldSetId 和 ETag
 → PATCH 三日期，If-Match + Idempotency-Key + 原因
 → CrmScheduleDateService：scope、白名单、幂等、外层事务
 → CollaborationService 新增三日期入口/锁后条件
 → 原执行锁和实物锁 → 当前有效性/版本核对
 → 原校验/正式保存/history/log
 → 数据库捕获：同事务 resource 修订 + capture
 → 保存后详情 + 新 ETag + 幂等结果同提交
 → PLM 原页面刷新可见；CRM 用响应更新
```

### 11.3 PLM 内部修改和删除/停用通知

```text
PLM 原页面/批量保存/导入/型号维护/编码维护/申请状态/生命周期
 → 原业务事务（原接口不变）
 → 新增数据库捕获分支：旧/新关系并集、版本、最小标记
 → 提交后 capture 对 Publisher 可见
 → Publisher 顺序发布 change_id
 → CRM GET /changes
    ├─ UPSERT/ENABLE → GET 当前详情 → 按实体 ID 更新
    ├─ DISABLE → 停止选择/编辑，保留既有引用
    └─ DELETE → 移出当前展示，不级联删 CRM 业务单据
```

## 12. 受影响模块与风险控制

| 模块 | 修改方式 | 直接/间接影响 | 必须控制 |
| --- | --- | --- | --- |
| CRM 新集成层 | 主要新增代码 | 独立接口、认证、错误格式、分页 | 新链不能被旧开关绕过；Filter 不重复注册 |
| 协作排程保存 | CollaborationService 小分支 | 共享原校验和事务核心 | 旧批量部分成功不改变，新 PATCH 全有或全无 |
| 模具/型号/编码/主档/申请/人员维护 | 不改原 API，数据库事务分支 | revision/capture 随原写入；可能增加锁与耗时 | 扇出完整、无敏感捕获、并发回归及性能评估 |
| 交付状态/延期 | 复用原规则、新增日切通知 | CRM 获得与页面一致结果 | 不把计划填入当完成，暂停不当停用 |
| 数据库迁移 | 只新增表、函数、触发器及索引 | 原表 DML 多了受控分支 | 默认关闭、schema 校验、锁定依赖、无破坏性回填 |
| PLM 前端 | 不改 | 刷新即可看到 CRM 保存正式值 | 不承诺已打开页面自动实时刷新 |
| CRM 调用方 | CRM 侧自行实现消费 | 游标、停用处理、回写冲突和重试 | 顺序应用、提交游标后才推进、防旧响应覆盖新值 |
| 旧推送模块 | 不改、不启用 | 若现场另行启用可能重复同步 | 上线核查旧配置，切换需单独授权，不静默改配置 |

重点风险及门槛：

- 源表触发器覆盖不全：版本与增量都会不可靠；以依赖矩阵和真实数据库测试作为上线门槛。
- 大范围品牌/产品修改扇出：先评估数据规模、执行计划、批量语句和事务耗时；不得用丢事件换速度。
- 新锁与旧锁死锁：真实并发测试、超时、有限重试和告警；不只用 Mockito 证明“有锁”。
- 集成失败影响旧保存：捕获开启后错误可使原事务回滚，必须有可操作的“关闭写入口、停止对外、提升世代、关闭捕获”流程，不允许吞异常继续伪装健康。
- 迁移安装 DDL 锁：在测试库验证并安排发布窗口，不把本次文档授权视为允许执行迁移。

## 13. 实施阶段与交付门槛

| 阶段 | 代码工作 | 验证门槛 | 能否称完整可用 |
| --- | --- | --- | --- |
| A：只落基础能力 | 新 DTO、查询、专用安全链、关闭状态的表/触发器 | 老接口回归、新接口默认关闭、映射正确 | 否 |
| B：版本及捕获 | 启用测试库捕获、初始化、Publisher、游标/日切 | 倒序提交/删除/停用/关系迁移无遗漏；读版本一致 | 否 |
| C：三日期回写 | 专用单行保存分支、锁后 guard、幂等、审计 | 部分/清空/并发/超时/暂停/停用全部通过 | 否，待双方联调 |
| D：联调与受控上线 | 独立账号/网络/凭证、CRM 全量补偿和异常恢复 | 双方验收与原业务回归通过 | 是 |

阶段用于控制增量，不删减 v3.0 的可靠性要求。只读阶段可以内部验证，不向 CRM 声称写入和通知已经可用；没有代码和环境测试依据，不承诺固定工期或容量。

## 14. 测试文件、测试方法与验收

### 14.1 拟新增测试

目录 `plm-server/src/test/java/com/yuewei/plm/module/integration/crm/openapi/`：

| 拟新增测试文件 | 核心验证 |
| --- | --- |
| `CrmOpenApiSecurityTest.java` | 认证、最小 scope、旧安全关闭仍保护新路由、普通用户 Token/CRM Token 跨接口互斥、上下文清理 |
| `CrmProjectionAssemblerTest.java` | 字段白名单、关系待确认、九位模具码、0185、标准空码不回退、暂停/待开工优先级 |
| `CrmScheduleDateServiceTest.java` | 三项白名单、显式 null/缺省区别、no-op、错误映射、原核心结果处理 |
| `CrmCursorCodecTest.java` | 签名、篡改、过期、不同实体/筛选/主体/世代、ID 数值边界 |
| `CrmRecordVersionCodecTest.java` | 跨对象/日期/权限世代版本不能误用 |
| `CrmIdempotencyServiceTest.java` | 规范化摘要、同键不同内容、原结果重放及保留期 |
| `CrmOpenApiPostgresIntegrationTest.java` | 源触发器、依赖扇出、真实保存/审计/回滚、删除后最小标记 |
| `CrmOpenApiConcurrencyIntegrationTest.java` | 多连接真实提交、锁冲突、并发幂等、PLM/CRM 同改、快照修订和发布水位 |
| `CrmOpenApiSyncIntegrationTest.java` | 当前全量 + S0 补偿、两个实体、日切、游标过期重建、停用/恢复顺序 |

原 `CollaborationService` 分支回归可在现有 `CollaborationLocalIntegrationTest` 附加用例，或新增同包 `CollaborationCrmDatesTest`，优先不重写旧测试构造方式。

### 14.2 必须复跑的既有测试

| 已存在文件 | 复跑原因 |
| --- | --- |
| `plm-server/src/test/java/com/yuewei/plm/module/collaboration/MoldScheduleServiceTest.java` | 原排程查询/保存及基准保持兼容 |
| `plm-server/src/test/java/com/yuewei/plm/module/collaboration/MoldSchedulePolicyTest.java` | 状态、建议日期及逾期规则未改变 |
| `plm-server/src/test/java/com/yuewei/plm/module/collaboration/MoldExecutionPolicyTest.java` | 暂停时计划和实际修改限制仍有效 |
| `plm-server/src/test/java/com/yuewei/plm/module/collaboration/CollaborationLocalIntegrationTest.java` | 原批量保存、导入、审计及事务语义 |
| `plm-server/src/test/java/com/yuewei/plm/module/moldreference/PhoneModelReferenceLocalIntegrationTest.java` | 原型号/品牌/编码维护和启停未被破坏 |
| `plm-server/src/test/java/com/yuewei/plm/module/masterdata/ProductMoldLifecycleLocalIntegrationTest.java` | 原生命周期与历史引用保护不改变 |

### 14.3 执行方式（仅供实施后使用，本次未执行）

在源码根目录先构建、再执行新增单测和既有规则单测，例如：

```powershell
mvn -f plm-server/pom.xml -DskipTests compile
mvn -f plm-server/pom.xml '-Dtest=CrmOpenApiSecurityTest,CrmProjectionAssemblerTest,CrmScheduleDateServiceTest,CrmCursorCodecTest,CrmRecordVersionCodecTest,CrmIdempotencyServiceTest,MoldScheduleServiceTest,MoldSchedulePolicyTest,MoldExecutionPolicyTest' test
```

真实数据库测试使用显式 opt-in 的隔离 PostgreSQL 测试库/临时 schema，不能指向生产。新测试环境变量建议 `PLM_CRM_OPENAPI_TEST_DB_URL`，地址加本地/隔离白名单；密码不输出、不写文档。倒序提交、崩溃和发布恢复需要多个独立事务，不能仅用包在统一回滚事务里的单连接测试替代。清理只针对已验证的测试 schema，不删除业务数据。

现有 LocalIntegrationTest 有各自严格环境门槛，需逐项核对启用条件；Maven 显示跳过不算通过。记录执行、跳过、失败数量和对应环境，不能只报告命令退出码。

### 14.4 最小端到端验收步骤

1. 查询一套测试模具详情，记录三个日期、其他日期、ETag、交付状态和延期。
2. 用原 ETag、唯一幂等键 PATCH 单个日期，验证另外两项和只读字段不变；PLM 页面刷新可见正式值。
3. 新取版本后分别用 null 清空三项，检查原计划基准未被重写，实际完成状态未被自动制造。
4. 重发成功请求同键同内容，业务历史只增加一次；同键改内容返回 409；失败事务不产生成功幂等结果。
5. 先查详情，再在 PLM 修改相关字段；旧版本 PATCH 返回 412，无覆盖。再测申请暂停、主档停用、部件实际状态并发变化。
6. 新建无申请/模具的标准型号，CRM 可查；初始编码 null，补充分配 0185 后同 ID 更新；旧历史码不顶替标准空码。
7. 两类资源分别停用/删除/启用（只用原系统允许动作），CRM 收到正确标记，已有 CRM 单据不被级联删除。
8. 全量分页中持续新增/修改/删除两类对象，完成最初 S0 后补偿，核对当前集合无遗漏；失败页不推进游标。
9. 制造事务 A 小 capture_id 晚提交、B 大号先提交；先消费 B，再提交 A，确认后续仍收到 A。另验证同实体停用后恢复不倒序。
10. 多 Publisher/处理中崩溃测试，确认没有未提交发布批次的水位空洞，也不丢未发布 capture。
11. 推进测试业务日期或重启日期任务，确认 asOfDate/延期变化被通知，同日任务重跑不重复制造无意义版本。
12. 使游标过期，确认 410；新全量失败时不清理旧副本，完整重扫及补偿完成后才停用缺失副本。
13. 对批量导入、大品牌改名/停用和日切任务记录 SQL 次数、事务耗时、锁等待与 pending 延迟，验证原页面和导入流程仍可用。

## 15. 发布、开关、回退与运维

以下是未来实施步骤，不代表本次获得执行授权。

- 所有新增路由默认关闭，配置不全或 capture/readiness 不成立时 fail closed，不因返回空数据而伪装同步成功。
- 分开控制对外 enabled、write-enabled、数据库 capture 和 Publisher；开启对外接口前，捕获和发布必须就绪。不能只关 worker 却长期让待处理事件无监控增长。
- 发布前备份及确认迁移对象，仅新增迁移；先隔离环境，再受控开放只读、最后写入。不得重启其他任务占用的服务或切换包。
- 健康指标包括 capture pending 数量/最老延迟、发布头、资源初始化数、依赖核对差异、版本冲突/幂等冲突、日切完成日、接口错误和数据库锁等待；日志不打印凭证或完整敏感请求。
- 回退首先关闭新 write-enabled，再关闭新 API；已成功回写的正式日期属于业务数据，**不自动撤销**，需要业务核对后使用正式更正流程。
- 若停止捕获，必须将控制状态标为不连续并提升 stream_epoch，后续恢复先初始化和核对，再要求 CRM 重新完整同步。不能持旧游标直接恢复运行。
- 不删除新表中的未消费捕获/成功幂等结果，不用回滚 SQL 删除已执行迁移；触发器停用和 schema 修复另走受控变更。老接口和页面应可继续按原契约使用。

## 16. 文档交付与后续实施清单

本方案已经明确：新增代码和文件、原文件修改位置、预期效果、调用链路、受影响模块、事务/并发设计、同步表、回归测试及受控上线方式。

实施前仍须完成三项技术核对，不是重新讨论已经确认的业务字段：

1. 以实际数据库 schema 核对依赖表/列、索引、数据量和现有迁移版本，冻结触发器映射及锁顺序；特别验证旧/新关联和祖先生命周期扇出。
2. 与 CRM 确定环境地址、服务主体、凭证交付、60 秒轮询、30 天变更保留、7 天幂等保留等技术参数，更新接口文档中的建议值。
3. 建立隔离 PostgreSQL 环境和可回滚的验收数据，按阶段执行测试并记录结果；未经验证不宣称“接口已经可调用”。

**本次只落本文档，不新增任何业务实现、迁移脚本或运行配置。**
