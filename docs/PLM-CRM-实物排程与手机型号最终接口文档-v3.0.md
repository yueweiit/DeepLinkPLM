# PLM–CRM 实物排程与手机型号最终接口文档

| 项目 | 内容 |
| --- | --- |
| 版本 / 日期 | v3.1 / 2026-11-11；文档文件名保留 v3.0 以兼容已有链接，HTTP 路径仍为 v3 |
| 最近文档修订 | products 与 skus 分离；productSchemaVersion=2；原日期写入协议及权限不变 |
| 提供方 / 调用方 | PLM / CRM |
| 基础路径 | /api/integrations/v3/crm |
| 实现源码 | D:/work/PLM_new/DeepLinkPLM-main |
| 当前交付状态 | v3.1 已实现并部署本地服务；50 项专项测试、19 项运行接口检查通过；源码默认开关仍关闭，未操作生产环境 |
| 文档效力 | 本文以实际代码为准，替代此前 v1/v2 及 v3 设计稿中的拟定接口细节 |

部署、修改文件和测试证据见 [实施与测试报告](./PLM-CRM-实物排程与手机型号实施与测试报告-v3.0.md)。除第 2.1 节明确标注的本地网络信息外，本文业务数据、记录 ID 及版本示例均为虚构；凭证占位符不能作为实际 API Key 使用。

## 1. 业务边界

CRM 查询 PLM「开模时间管控 → 实物排程」，每条记录是一套实物 moldSetId，不是开模申请单。PC、TPU 和多个兼容手机不重复生成实物套。

CRM 只能回写、修改或清空三个日期：

| 页面字段 | JSON 字段 |
| --- | --- |
| 实际抵墨 | dates.actualMexicoArrivalDate |
| 计划验收 | dates.plannedAcceptanceDate |
| 计划试模量产 | dates.plannedProductionDate |

其他表格字段、产品/手机/模具编码、负责人及状态全部只读。PLM 仍可按原权限人工修改三个日期；两端使用同一正式业务数据，不维护互不一致的副本。

另提供独立标准手机型号查询；没有申请、模具或编码的新型号也能查询。首次同步当前有效数据，包括接入前创建且仍有效的记录；不迁移接入前已删除对象、历史版本或操作历史。

本版采用 CRM 主动查询及轮询变更，不是 PLM 主动推送/Webhook，也无需 CRM 提供接收通知地址。

## 2. 接口清单与认证

### 2.1 本地局域网测试地址与 API Key

PLM 与 CRM 的本地测试版本位于同一局域网，由 CRM 后端通过 CRM 专用网关调用 PLM 后端。以下为 2026-11-11 实际加载并验证的本地配置：已从 PLM 电脑通过局域网 IP 调用验证，CRM 所在电脑的连通性仍需单独确认。

| 配置项 | 本地联调约定 / 当前状态 |
| --- | --- |
| PLM 电脑局域网 IP | `192.168.5.71`（2026-11-11 核验；网络切换或 DHCP 分配变化后须重新确认） |
| PLM 现有前端测试地址 | 本机 `http://127.0.0.1:8111`；局域网 `http://192.168.5.71:8111`（仅可信局域网开发测试） |
| CRM 专用网关地址 | `http://192.168.5.71:8080`（仅代理下列 CRM v3 路径；首页及原业务接口返回 404） |
| CRM 接口基础地址 / Base URL | `http://192.168.5.71:8080/api/integrations/v3/crm` |
| CRM 测试 API Key | 本地已签发，查询与三个约定日期字段写入的配置均已准备。Token 原文见下方交付说明；接口无内置默认 Key |
| API Key 使用方式 | `Authorization: Bearer <实际签发的CRM测试Token原文>`；不是 URL 参数或 `X-API-Key` 请求头 |
| 本地服务账号 | `crm-local-service`，userId=`14`；非管理员、无网页登录密码，domestic / all_objects=true，schedule:read + schedule:write，logistics/mexico 职责 |
| 当前可用性 | 本地后端 local-crm-product-sku-20261111-r2；19 项检查通过，含产品/SKU 拆分、查询、鉴权和日期写入前置条件；不替代 CRM 实际页面验收 |

本机测试凭证交付位置（仅为本机路径，不是 HTTP 下载地址）：

- CRM 后端开发人员取 `D:/work/PLM_new/20261017-c856_bHNzr/local-runtime/.env.crm-openapi.crm.local` 中的 `PLM_API_TOKEN` 原文；该文件也包含约定的 `PLM_BASE_URL`。只通过安全渠道交付，不提交到仓库或前端。
- PLM 专用配置在同目录 `.env.crm-openapi.plm.local`，仅含 Token 摘要与独立签名密钥等服务端配置，不向 CRM 交付。
- 同目录 `compose.crm-openapi.yaml` 已加载 PLM 私有配置，并新增绑定 `192.168.5.71:8080` 的 CRM 专用网关。后续使用 `start-crm.cmd` 或相同 Compose 文件组合启动，避免旧启动方式丢失 CRM 配置。详情见 `README.crm-openapi.md`。
- 本地已备份运行 JAR 与数据库；沿用已创建的服务账号及同步初始化。本次只增量替换 CRM 两个类和新增一份迁移，保留其他功能；已部署本地后端并恢复既有 CRM/前端局域网配置。测试没有改动排程日期，未修改生产环境。

本地完整接口地址：

| 用途 | 方法与 URL |
| --- | --- |
| 实物排程列表 | `GET http://192.168.5.71:8080/api/integrations/v3/crm/mold-schedules` |
| 实物排程详情 | `GET http://192.168.5.71:8080/api/integrations/v3/crm/mold-schedules/{moldSetId}` |
| CRM 三个日期回传 | `PATCH http://192.168.5.71:8080/api/integrations/v3/crm/mold-schedules/{moldSetId}/crm-dates` |
| 手机型号与编码列表 | `GET http://192.168.5.71:8080/api/integrations/v3/crm/phone-models` |
| 手机型号详情 | `GET http://192.168.5.71:8080/api/integrations/v3/crm/phone-models/{phoneModelId}` |
| 增量变更查询 | `GET http://192.168.5.71:8080/api/integrations/v3/crm/changes` |

CRM 后端环境配置示意（变量名为 CRM 侧建议名称，不是 PLM 已实现的环境变量）：

```dotenv
PLM_BASE_URL=http://192.168.5.71:8080/api/integrations/v3/crm
PLM_API_TOKEN=<从本地CRM交付文件取得的测试Token原文>
```

测试 API Key 启用约定：

1. 为本地测试单独生成至少 32 随机字节的 Token。原文通过安全渠道交付 CRM 并保存在 CRM 后端环境配置中，不提交到仓库、本文档或前端代码；本文只记录发放/启用状态。
2. PLM 配置该 Token 原文的 SHA-256 十六进制摘要到 `PLM_CRM_OPENAPI_TOKEN_DIGESTS`。**CRM 发送原文，不发送摘要**。`PLM_CRM_OPENAPI_SIGNING_SECRET` 是 PLM 独立签名密钥，不是 API Key，不交付 CRM。
3. 按实施与测试报告配置 CRM 专用最小权限账号、签名密钥与读权限，并启用 `PLM_CRM_OPENAPI_ENABLED=true`；日期回传另需 `PLM_CRM_OPENAPI_WRITE_ENABLED=true`、写 scope 及相应账号权限/职责。仅填写 Token 不能完成启用。
4. 在受控本地测试数据库应用新增迁移并完成一次同步初始化。初始化有锁表行为，应按实施报告在维护窗口执行，不对生产库直接操作，也不在每次启动时重复执行。
5. 网关必须监听局域网可达的地址；本地采用 `192.168.5.71:8080` 的 CRM 专用网关，后端仍仅在 Docker 内部网络监听。Windows 防火墙按需仅放行可信来源。从 CRM 电脑携带有效 Token 查询手机型号，确认返回 HTTP 200 后完成跨电脑联通验收。

网络与使用注意：

- CRM 运行在另一台电脑时，不得把 PLM 地址填成 `localhost` 或 `127.0.0.1`，它们指向 CRM 所在电脑。
- 当前无需启动 `5173` 开发服务；PLM 前端使用 `127.0.0.1:8111` 或局域网 `192.168.5.71:8111`。局域网 `8080` 仅为 CRM 专用入口，不开放原前端、登录或管理接口。
- 本地 HTTP 仅限受控、可信局域网临时测试，Token 会随 HTTP 明文传输；不要使用生产凭证，不要暴露公网。正式环境必须使用 HTTPS，测试结束后撤销测试 Token。
- 建议由 CRM 后端发起请求，不将服务 Token 放在 CRM 前端。浏览器跨端口直连接口仍属于跨域，本版未配置该能力。
- 如果实际后端端口或 PLM 电脑 IP 改变，须同步更新本节及 CRM 的 `PLM_BASE_URL`。

### 2.2 接口清单与通用认证

| 方法 | 相对路径 | 权限 |
| --- | --- | --- |
| GET | /mold-schedules | crm:mold-schedule:read |
| GET | /mold-schedules/{moldSetId} | crm:mold-schedule:read |
| PATCH | /mold-schedules/{moldSetId}/crm-dates | crm:mold-schedule:read + crm:schedule-dates:write |
| GET | /phone-models | crm:phone-model:read |
| GET | /phone-models/{phoneModelId} | crm:phone-model:read |
| GET | /changes | 上述两项 read 权限 |

通用请求头：

```http
Authorization: Bearer <独立CRM服务凭证>
Accept: application/json
```

- 正式环境使用 HTTPS，JSON 编码为 UTF-8。
- 服务凭证通过安全渠道线下发放。PLM 配置其 SHA-256 摘要，不存明文；允许同时配置多个摘要用于轮换。删除摘要并使配置生效即撤销凭证。本版不提供 OAuth、登录换 Token 或自动过期的签发接口。
- 独立服务账号必须为启用、未删除、非管理员的 domestic 账号，all_objects=true。账号权限只允许 schedule:read、schedule:write、collaboration:view、project:view、product:view 这五项的子集，并必须含 schedule:read。
- 开放写入还需要 write-enabled=true、写 scope、schedule:write，以及 logistics、mexico 两个填写职责。缺少职责时不会以管理员身份代写。
- 普通用户登录 Token 不适用于本接口；CRM 服务 Token 不是老页面接口的登录 Token。旧系统关闭登录校验的配置不影响本接口强制认证。
- 不授予 CRM 新建/分码/删除/停用型号、修改负责人、管理账号、费用和附件等权限。
- 本版是服务端对服务端接口，未配置浏览器跨域访问能力。

公共数据约定：

| 项目 | 实际约定 |
| --- | --- |
| 日期 | YYYY-MM-DD；合法公历日期，年份 0001–9999，不含时刻 |
| 时间戳 | 带时区的 ISO 8601；可能返回 Z 或其他明确偏移 |
| 业务日期 | Asia/Shanghai；延期以 PLM 的 asOfDate 为准 |
| ID / 编码 | 字符串；套次和逾期天数等计数仍为整数 |
| 缺值 | null；无关联为 []，不猜测编码或替代日期 |
| 成功响应 | HTTP 200，正文最外层为 {"data": ...} |
| 缓存 | Cache-Control: no-store；详情及 PATCH 返回 ETag |
| 跟踪 | 响应头 X-Request-Id；业务错误正文同时含 requestId |

稳定身份键为 (sourceSystem, entityType, recordId)。编码、名称、assetNumber 仅供业务展示和查询，不可替代回写目标 moldSetId。

## 3. 实物排程查询

### 3.1 列表

GET /api/integrations/v3/crm/mold-schedules

| 参数 | 说明 |
| --- | --- |
| pageSize | 默认 50，整数 1–100 |
| pageToken | 下一页令牌，第一页不传 |
| moldSetId / assetNumber | 精确匹配，例如 597 / MS-000597 |
| productCode | 通过产品线或已建立项目关系精确匹配产品编码 |
| phoneModelId / phoneModelCode | 基于本套明确关联或未删除来源申请的标准型号，精确匹配 |
| moldCode | 对本套部件的目录编码/来源原码作精确匹配，不按名字猜码 |
| deliveryStatus | 第 6 节枚举 |
| overdueOnly | true 或 false，默认 false |
| sortOrder | 仅实物排程列表支持：`asc` 或 `desc`；省略时为 `asc`。`desc` 按实物编号的数字部分（即 `moldSetId`）从大到小排列 |
| keyword | 最多 128 字符；款式、产品线名称/编码、套级标准型号/品牌/编码、历史展示文字、实物编号和部件编码搜索 |
| includeInactive | true 或 false，默认 false；true 包含停用但未删除记录 |

参数以 AND 组合。未知参数返回 400；后续翻页必须保持相同参数及页大小，包括显式传入的默认值，不得跨实体复用令牌。

```json
{
  "data": {
    "items": [],
Welcome to Alibaba Cloud Elastic Compute Service !

Last login: Tue Nov 10 17:40:24 +0800 2026 from 100.104.143.1
The current terminal create by BT-Panel.
root@iZzurmoqt8xhn0Z:~# bash -e <<'SH'
cd /var/lib/yuewei-plm/uploads/supplier-photo-20261111
test ! -e supplier-single-production-20261111
python3 -m zipfile -e supplier-single-production-20261111.zip .
cd supplier-single-production-20261111
sha256sum -c SHA256SUMS
python3 deploy.py --build
python3 deploy.py --apply
SH
Dockerfile.web: OK
.dockerignore: OK
deploy.py: OK
runtime.py: OK
README.md: OK
web/index.html: OK
web/assets/ProductCatalogEntry-single-20261111-r1.js: OK
BACKUP_DIRECTORY: /var/lib/yuewei-plm/backups/supplier-single-20261111/20261111T035622163489Z
BUILDING: web
BUILD_OK: web built; no services restarted
PRIVATE_LOG_DIRECTORY: /var/lib/yuewei-plm/backups/supplier-single-20261111/20261111T035622163489Z
BACKUP_DIRECTORY: /var/lib/yuewei-plm/backups/supplier-single-20261111/20261111T035626997038Z
CONFIG_DIFF_OK: only web image changes; server and coding flag retained
RECREATING_SERVICE: web
DEPLOY_OK: single-product buttons published; server unchanged; database UP; API 401
ACTIVE_COMPOSE_OVERRIDE: /var/lib/yuewei-plm/releases/20261017-c856/supplier-single-20261111-r1.override.json
PRIVATE_LOG_DIRECTORY: /var/lib/yuewei-plm/backups/supplier-single-20261111/20261111T035626997038Z
root@iZzurmoqt8xhn0Z:~# 
Welcome to Alibaba Cloud Elastic Compute Service !

Last login: Tue Nov 10 17:40:24 +0800 2026 from 100.104.143.1
The current terminal create by BT-Panel.
root@iZzurmoqt8xhn0Z:~# cd /var/lib/yuewei-plm/releases/20261017-c856

docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
docker exec plm-server sha256sum /app/app.jar
docker logs --tail 100 plm-server
NAMES                                   IMAGE                                          STATUS
fifo-inventory                          fastapiproject-web                             Up 2 hours
plm-web                                 yuewei-plm-web:20261111-supplier-single-r1     Up 4 hours
plm-server                              yuewei-plm-server:20261111-supplier-photo-r1   Up 4 hours
eims-frontend                           yw-eims-frontend                               Up 3 weeks
eims-backend                            yw-eims-backend                                Up 3 weeks
phone-watch-worker                      yuewei-plm-phone-watch:20261017-c856           Up 3 weeks
yuewei-plm-master-data-sync-1           yuewei-plm-server:20261017-c856-rehearsal      Up 9 days
phone-watch                             yuewei-plm-phone-watch:20261017-c856           Up 3 weeks
jsherp-web                              jsherp-web                                     Up 4 weeks
jsherp-app                              jsherp-app                                     Up 4 weeks (healthy)
jsherp-postgres                         postgres:15                                    Up 4 weeks (healthy)
jsherp-redis                            redis:7-alpine                                 Up 4 weeks (healthy)
eims-postgres                           postgres:16-alpine                             Up 6 weeks (healthy)
crm-logistics-message-center-funasr-1   crm-logistics-message-center-funasr:local      Up 2 months (healthy)
plm-redis                               redis:7-alpine                                 Up 3 weeks
dingtalk-oa-kafka                       apache/kafka:latest                            Up 2 months
freight-calculator-web                  freight-calculator-web                         Up 2 months
freight-calculator-api                  freight-calculator-api                         Up 2 months
oem-crm-redis                           redis:7-alpine                                 Up 6 weeks
oem-crm-postgres                        postgres:16-alpine                             Up 6 weeks (healthy)
oem-crm-minio                           minio/minio:RELEASE.2025-04-22T22-12-26Z       Up 6 weeks
860b581ab5610288a0fca330807644c48a941c11d5f551007c6e858042e6fcc5  /app/app.jar

  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

 :: Spring Boot ::                (v3.5.0)

2026-11-11 11:42:42.985 [main] INFO  [no-request] com.yuewei.plm.PlmApplication - Starting PlmApplication v0.0.1-SNAPSHOT using Java 17.0.19 with PID 1 (/app/app.jar started by plm in /app)
2026-11-11 11:42:42.988 [main] INFO  [no-request] com.yuewei.plm.PlmApplication - The following 1 profile is active: "prod"
2026-11-11 11:42:44.473 [main] INFO  [no-request] o.s.d.r.c.RepositoryConfigurationDelegate - Multiple Spring Data modules found, entering strict repository configuration mode
2026-11-11 11:42:44.475 [main] INFO  [no-request] o.s.d.r.c.RepositoryConfigurationDelegate - Bootstrapping Spring Data Redis repositories in DEFAULT mode.
2026-11-11 11:42:44.527 [main] INFO  [no-request] o.s.d.r.c.RepositoryConfigurationDelegate - Finished Spring Data repository scanning in 37 ms. Found 0 Redis repository interfaces.
2026-11-11 11:42:45.253 [main] INFO  [no-request] o.s.b.w.e.tomcat.TomcatWebServer - Tomcat initialized with port 8080 (http)
2026-11-11 11:42:45.266 [main] INFO  [no-request] o.a.catalina.core.StandardService - Starting service [Tomcat]
2026-11-11 11:42:45.267 [main] INFO  [no-request] o.a.catalina.core.StandardEngine - Starting Servlet engine: [Apache Tomcat/10.1.41]
2026-11-11 11:42:45.294 [main] INFO  [no-request] o.a.c.c.C.[Tomcat].[localhost].[/] - Initializing Spring embedded WebApplicationContext
2026-11-11 11:42:45.295 [main] INFO  [no-request] o.s.b.w.s.c.ServletWebServerApplicationContext - Root WebApplicationContext: initialization completed in 2245 ms
2026-11-11 11:42:45.617 [main] INFO  [no-request] o.s.b.web.servlet.RegistrationBean - Filter simpleTokenAuthenticationFilterRegistration was not registered (disabled)
2026-11-11 11:42:45.850 [main] INFO  [no-request] com.zaxxer.hikari.HikariDataSource - HikariPool-1 - Starting...
2026-11-11 11:42:46.057 [main] INFO  [no-request] com.zaxxer.hikari.pool.HikariPool - HikariPool-1 - Added connection org.postgresql.jdbc.PgConnection@b548f51
2026-11-11 11:42:46.059 [main] INFO  [no-request] com.zaxxer.hikari.HikariDataSource - HikariPool-1 - Start completed.
2026-11-11 11:42:46.092 [main] INFO  [no-request] org.flywaydb.core.FlywayExecutor - Database: jdbc:postgresql://172.19.49.226:5432/plm_data_management?currentSchema=plm (PostgreSQL 17.10)
2026-11-11 11:42:46.326 [main] INFO  [no-request] o.f.core.internal.command.DbValidate - Successfully validated 119 migrations (execution time 00:00.202s)
2026-11-11 11:42:46.360 [main] INFO  [no-request] o.f.core.internal.command.DbMigrate - Current version of schema "plm": 20261109.2010
2026-11-11 11:42:46.364 [main] INFO  [no-request] o.f.core.internal.command.DbMigrate - Schema "plm" is up to date. No migration necessary.
 _ _   |_  _ _|_. ___ _ |    _ 
| | |\/|_)(_| | |_\  |_)||_|_\ 
     /               |         
                        3.5.7 
2026-11-11 11:42:48.575 [main] WARN  [no-request] o.s.b.a.s.s.UserDetailsServiceAutoConfiguration - 

Using generated security password: 3ebfe039-952e-442b-9533-69b227530226

This generated password is for development use only. Your security configuration must be updated before running your application in production.

2026-11-11 11:42:48.583 [main] INFO  [no-request] o.s.s.c.a.a.c.InitializeUserDetailsBeanManagerConfigurer$InitializeUserDetailsManagerConfigurer - Global AuthenticationManager configured with UserDetailsService bean with name inMemoryUserDetailsManager
2026-11-11 11:42:49.301 [main] INFO  [no-request] o.s.b.w.e.tomcat.TomcatWebServer - Tomcat started on port 8080 (http) with context path '/'
2026-11-11 11:42:49.317 [main] INFO  [no-request] com.yuewei.plm.PlmApplication - Started PlmApplication in 6.898 seconds (process running for 7.512)
2026-11-11 11:42:50.857 [http-nio-8080-exec-1] INFO  [no-request] o.a.c.c.C.[Tomcat].[localhost].[/] - Initializing Spring DispatcherServlet 'dispatcherServlet'
2026-11-11 11:42:50.857 [http-nio-8080-exec-1] INFO  [no-request] o.s.web.servlet.DispatcherServlet - Initializing Servlet 'dispatcherServlet'
root@iZzurmoqt8xhn0Z:/var/lib/yuewei-plm/releases/20261017-c856# docker logs plm-server 2>&1 | grep -iE 'flyway|schema.*version' | tail -30
2026-11-11 11:42:46.092 [main] INFO  [no-request] org.flywaydb.core.FlywayExecutor - Database: jdbc:postgresql://172.19.49.226:5432/plm_data_management?currentSchema=plm (PostgreSQL 17.10)
root@iZzurmoqt8xhn0Z:/var/lib/yuewei-plm/releases/20261017-c856# 
    "hasMore": false,
    "nextPageToken": null,
    "syncCursor": "<不透明同步起点>"
  }
}
```

默认按数值 ID 升序 keyset 翻页，首次固定扫描最大 ID；传入 `sortOrder=desc` 时按数值 ID 倒序 keyset 翻页。两种方向的分页令牌都会固定排序方向、筛选和首屏上界，连续翻页不会因方向切换而重复或漏掉 ID；列表不是跨多页的历史快照。每个分页令牌有效 24 小时，超过期限返回 410。只有无业务筛选、includeInactive=false 的完整扫描才返回 syncCursor；其余查询为 null。旧版未传 `sortOrder` 的调用保持原升序行为。

后台全量/补同步应不传 `sortOrder`，继续使用升序；需要最新实物编号优先展示的 CRM 页面可显式传 `sortOrder=desc`。该参数只改变列表顺序，不改变详情、回写权限或变更游标。

### 3.2 详情与字段

GET /api/integrations/v3/crm/mold-schedules/597

| 字段 | 类型与含义 |
| --- | --- |
| sourceSystem / entityType | 固定 PLM / MOLD_SET |
| recordId / moldSetId | 实物套 ID，字符串且相同 |
| itemId | 所属协作主档 ID，字符串 |
| styleName | 页面款式口径，优先产品线名称 |
| assetNumber / setNo | 展示编号 / 套次整数 |
| productSchemaVersion | 整数，固定 2；表示产品与 SKU 已分离 |
| products | 产品线或型号项目数组，不再混入具体 SKU；字段见下方 |
| skus | 具体 SKU 数组，无已建立关联的 SKU 时为 []；字段见下方 |
| compatiblePhones | phoneModelId、brandName、modelName、phoneModelCode、lifecycleStatus、relationStatus |
| compatiblePhonesText | 展示文字；保留待核对/适配待确认提示，不作为关联键 |
| owner | null 或 userId、displayName；不包含联系方式 |
| dates | 下表列出的七项日期，均为日期字符串或 null |
| deliveryStatus / deliveryStatusText | 机器状态 / 中文展示，PLM 计算 |
| overdue / overdueTasks | 是否有逾期节点 / 节点明细数组 |
| moldParts | PC、TPU 两项；partType、materialCode、moldCode |
| lifecycleStatus | ACTIVE 或 INACTIVE；删除对象详情为 404 |
| recordVersion | v3- 后接 64 位小写十六进制的不透明聚合版本 |
| updatedAt | 接口聚合修订时间，包含依赖变化，不仅是源套 updated_at |
| asOfDate | 本次延期计算业务日 |
| writableFields | 当前允许的日期键；只读/停用为空数组，暂停最多只含 actualMexicoArrivalDate |

产品与 SKU 分离约定（2026-11-11 起）：

- `products` 和 `skus` 在排程列表、详情、成功 PATCH 的新响应中保持同一结构，均只读；CRM 应分别入产品/项目表与 SKU 表，不再将旧 products 全部当作产品。
- 关联范围保持原口径：所属协作主档的 product_line_id 与显式 item_project 关系；同一记录按 ID 去重、升序返回。同一主档关联会出现在其不同实物套中，不宣称已确认该 SKU 与每套模具的适配。不会遍历产品线下所有 SKU，也不会按名称、N 前缀或手机码自动补造关系。
- 显式 `product_type=sku` 归入 skus；历史 `model_variant` 复用系统 PhoneProductEvidence 判定：project_kind 为空，finished_product_code 非空，且 color 非空时归入 skus。其余 product_line/model_variant 保留在 products，包括未编码的在研项目。不是根据编码前缀猜成品或把所有 N 码当成 SKU。
- 删除记录不返回；未删除但停用记录携带 masterDataStatus=inactive，CRM 应按当前数组替换旧关联集合，而非只追加。同一 ID 可能因业务分类变化从一个数组移到另一个数组。
- SKU 的 skuCode 优先使用源 finished_product_code 非空原文，否则使用源 product_code；不自动生成、截断、补零或改写。phoneModelCode/colorCode 为源 SKU 自身字段，不替换成共模基准手机编码；缺少则为 null。该字段不同于 compatiblePhones 中当前标准型号编码，不承诺其主数据启停状态相同。
- 原 productCode 查询条件仍匹配原关联范围中的源 product_code，保留旧筛选语义；未新增独立产品/SKU全量接口或推送机制。

| products 元素字段 | 类型 / 含义 |
| --- | --- |
| productId / productCode / productName | 字符串 ID / 源产品编码 / 产品或型号项目名称；保留原键名 |
| productType | product_line 或 model_variant，源业务类型 |
| parentProductId | 字符串或 null，源直接父节点 ID，不自动提升为产品线 |
| masterDataStatus | active 或 inactive |

| skus 元素字段 | 类型 / 含义 |
| --- | --- |
| skuId / skuCode / skuName | 字符串源 plm_product ID / 来源 SKU 编码 / 来源名称 |
| productType | sku 或 model_variant（历史颜色 SKU） |
| parentProductId | 字符串或 null，源直接父节点 ID；可能指向产品线或型号项目，不保证它在同一 products 数组 |
| finishedProductCode | 源成品码字符串或 null，不额外生成 |
| phoneModelId / phoneModelCode | 源 SKU 自身标准型号 ID（字符串或 null）/ 自身型号编码（字符串或 null，0185 保留前导零） |
| color / colorCode | 源颜色名称 / 源颜色码，字符串或 null |
| masterDataStatus | active 或 inactive |

兼容注意：这是按需求对 products 内容进行的收敛，已同步 SKU 会从旧 products 中移出，CRM 需要同时消费 skus。发布迁移为当前已跟踪且未删除的实物排程补一条变更，保留现有 epoch/cursor，CRM 可沿 /changes 重新查询详情；无须重置同步流。ETag 会变化，回写前重新 GET 获取。升级前成功请求的幂等重放仍返回原保存的响应，不会伪造新响应；新请求使用新幂等键，不要拿重放响应替代最新详情。

| dates 子字段 | 中文含义 | CRM 可写 |
| --- | --- | --- |
| openedDate | 开模日期 | 否 |
| plannedMoldDeliveryDate | 计划模具交付 | 否 |
| actualShipDate | 实际发模 | 否 |
| plannedFactoryArrivalDate | 计划到墨工厂 | 否 |
| actualMexicoArrivalDate | 实际抵墨 | 是 |
| plannedAcceptanceDate | 计划验收 | 是 |
| plannedProductionDate | 计划试模量产 | 是 |

关联状态 relationStatus：

| 值 | 含义 |
| --- | --- |
| CONFIRMED | 套级 PC/TPU 均确认适配并有相应依据 |
| PENDING_CONFIRMATION | 已有关联，但确认或依据不齐全 |
| APPLICATION_SOURCE | 仅来自来源申请，不宣称已确认适配 |

手机码取当前标准编码；未分配、编码停用或删除时为 null，不能用历史申请码补齐。型号本身停用/删除时，已有套级引用可保留并携带其生命周期。纯自由文本只用于展示。

完整模具编码复用系统校验，例 MBA101199 = MBA + 10 + 1199；TPU=10、PC=20。不合法/不匹配部件的完整码返回 null。MBA10 等五位目录前缀不是完整码。手机编码 0185 始终是字符串，不自动补零或改写来源编码。

响应示例（版本仅为格式示例，实际必须取 GET 返回值）：

```json
{
  "data": {
    "sourceSystem": "PLM",
    "entityType": "MOLD_SET",
    "recordId": "597",
    "moldSetId": "597",
    "itemId": "300",
    "styleName": "亮甲2.0",
    "assetNumber": "MS-000597",
    "setNo": 1,
    "productSchemaVersion": 2,
    "products": [{"productId": "35", "productCode": "MBA", "productName": "亮甲2.0", "productType": "product_line", "parentProductId": null, "masterDataStatus": "active"}],
    "skus": [{"skuId": "1001", "skuCode": "NBA4030119901", "skuName": "亮甲2.0 示例型号 黑色", "productType": "model_variant", "parentProductId": "35", "finishedProductCode": "NBA4030119901", "phoneModelId": "88", "phoneModelCode": "1199", "color": "黑色", "colorCode": "01", "masterDataStatus": "active"}],
    "compatiblePhones": [
      {"phoneModelId": "88", "brandName": "示例品牌", "modelName": "示例型号", "phoneModelCode": "1199", "lifecycleStatus": "ACTIVE", "relationStatus": "CONFIRMED"}
    ],
    "compatiblePhonesText": "示例品牌 示例型号 · 1199",
    "owner": {"userId": "12", "displayName": "示例负责人"},
    "dates": {
      "openedDate": "2026-09-29",
      "plannedMoldDeliveryDate": "2026-10-20",
      "actualShipDate": "2026-10-23",
      "plannedFactoryArrivalDate": "2026-10-27",
      "actualMexicoArrivalDate": "2026-10-28",
      "plannedAcceptanceDate": "2026-11-11",
      "plannedProductionDate": "2026-11-14"
    },
    "deliveryStatus": "mexico_arrived",
    "deliveryStatusText": "抵达墨西哥",
    "overdue": true,
    "overdueTasks": [
      {"taskKey": "factory_arrival", "title": "确认 PC / TPU 实际到厂", "dueField": "plannedFactoryArrivalDate", "dueDate": "2026-10-27", "overdueDays": 13}
    ],
    "moldParts": [
      {"partType": "PC", "materialCode": "20", "moldCode": "MBA201199"},
      {"partType": "TPU", "materialCode": "10", "moldCode": "MBA101199"}
    ],
    "lifecycleStatus": "ACTIVE",
    "recordVersion": "v3-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "updatedAt": "2026-11-09T02:00:00Z",
    "asOfDate": "2026-11-09",
    "writableFields": ["actualMexicoArrivalDate", "plannedAcceptanceDate", "plannedProductionDate"]
  }
}
```

响应头 ETag 是 recordVersion 外加双引号。版本可能因相关资料、生命周期或业务日变化而改变；不能比较版本的大小或自行生成。

## 4. CRM 三日期回写

PATCH /api/integrations/v3/crm/mold-schedules/597/crm-dates

```http
Authorization: Bearer <独立CRM服务凭证>
Content-Type: application/json
If-Match: "<刚查询到的recordVersion>"
Idempotency-Key: crm-schedule-597-20261109-001
```

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

仅清空计划验收、保留另外两项：

```json
{
  "dates": {"plannedAcceptanceDate": null},
  "reason": "原计划取消，待重新确认",
  "crmReference": "CRM-SCHEDULE-0002"
}
```

| 项目 | 保存规则 |
| --- | --- |
| dates | 必填非空对象，仅三个白名单日期键；缺省不改，null 清空，空字符串非法 |
| reason | 必填非空字符串，最多 2000 字符，去首尾空白 |
| crmReference | 必填非空字符串，最多 128 字符，去首尾空白，不替代幂等键 |
| operator | 可选对象，仅 id/name，各为非空且不超过 128 字符的字符串；辅助审计，不作为身份 |
| 正文 | 不超过 32768 个字符；重复 JSON 键、未知字段、拼接多份 JSON 一律拒绝 |
| If-Match | 必填，带双引号的完整 ETag；不接受 *、弱 ETag 或裸版本 |
| Idempotency-Key | 必填，1–128 字符，仅字母、数字、点、下划线、冒号和短横线 |
| 原子性 | 单请求全部成功或回滚；沿用原业务字段组、日期、部件和执行校验 |
| 暂停 | 禁止修改/清空计划日期；实际抵墨仍受原暂停补录规则约束 |
| 停用/删除 | 不允许回写；返回 409 / 404 |

成功 HTTP 200，data 与排程详情完全同结构，并返回保存后的 ETag。不自动生成建议日期、不修改既有计划基准，不允许 CRM 提交 deliveryStatus 等状态字段。所有值未变化时不新增业务修订/历史，但保存本次幂等结果。

### 4.1 并发与幂等

1. 服务端在锁内检查当前版本，再调用原正式保存核心。PLM 人工保存、相关资料变化或自然日变化使旧 ETag 失效时返回 412，不覆盖刚保存的数据。
2. 同一服务主体的幂等键绑定 PATCH 方法、目标 moldSetId、If-Match 以及规范化 JSON 正文；JSON 属性顺序不影响匹配，值及内容变化会影响匹配。
3. 同键同内容重试返回原已提交成功结果；先查成功记录，再校验当前业务版本。原结果可能旧于当前数据，需要最新值时另行 GET。
4. 同键改内容返回 409 IDEMPOTENCY_KEY_REUSED。失败事务不写入成功幂等记录。同键并发请求只执行一次成功修改。
5. 成功幂等结果默认保存 7 天，配置不允许少于 7 天；不保证超过保留期仍可重放。
6. 超时/5xx 后保留原正文、原 ETag、原幂等键重试。412 后必须重新查询并核对业务，再使用新 ETag 和新键提交，不能自动强制覆盖。
7. 重试仍先验证现有凭证、scope、账号及读写开关，不以缓存绕过撤权。序列化冲突等数据库争用最多整事务重试两次，仍失败返回 503。

## 5. 独立手机型号查询

GET /api/integrations/v3/crm/phone-models

参数：pageSize、pageToken、phoneModelId、phoneModelCode、brandId、keyword、includeInactive。分页规则同排程。ID/编码精确匹配，keyword 最多 128 字符，按标准型号名或品牌名搜索。

GET /api/integrations/v3/crm/phone-models/188

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
    "createdAt": "2026-11-09T01:00:00Z",
    "updatedAt": "2026-11-09T02:00:00Z",
    "lifecycleStatus": "ACTIVE",
    "recordVersion": "v3-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  }
}
```

未分码时仅 phoneModelCode 为 null，不隐藏新型号。后续分码更新同一 ID 并产生变更；编码停用/删除后返回 null，型号不会仅因无编码自动停用。

## 6. PLM 自动计算状态与延期

| deliveryStatus | 展示 |
| --- | --- |
| pending / making / ready | 待开模 / 制作中 / 待发模 |
| shipped / mexico_arrived | 已发出 / 抵达墨西哥 |
| partially_arrived / awaiting_acceptance | 部分到厂 / 已到厂，待验收 |
| partially_accepted / acceptance_failed | 部分验收 / 验收不通过 |
| delivered / market_ready | 交付完成 / 已确认上市 |
| paused / planned | 已暂停 / 待开工 |

暂停、待开工优先展示，其后复用正式排程状态。抵墨日期不会覆盖已有到厂/验收等更后续事实，也不代表验收完成。计划验收、计划试模量产不是实际完成；清空计划也不是节点完成。

overdueTasks 每项包含 taskKey、title、dueField、dueDate、overdueDays。没有未完成且已过期的有效计划节点时，overdue=false、overdueTasks=[]。不按建议日期替代空计划计算逾期。

每天按 Asia/Shanghai 推进延期结果。后台每 60 秒处理最多 200 套日期补偿，积压时分批推进；因此大批量日切不承诺一分钟全部通知完成。查询的 asOfDate 始终是计算日，日切修订由变更流补偿。

## 7. 生命周期、首次同步与增量

### 7.1 生命周期映射

| 资源 | ACTIVE / INACTIVE / DELETED |
| --- | --- |
| 手机型号 | 型号自身删除/不存在为 DELETED；型号或品牌停用、品牌缺失/删除为 INACTIVE；否则 ACTIVE |
| 实物套 | 套或所属档案删除/不存在为 DELETED；套/档案停用为 INACTIVE；对当前有效套继续检查所属产品祖先，停用/关系循环为 INACTIVE，祖先缺失/删除使其移除为 DELETED；否则 ACTIVE |

暂停、待开工、逾期、无编码、交付完成都不是主数据停用。手机停用不会自动停用实物套，但会更新套级关联型号的有效性及版本。

默认列表只返回 ACTIVE；includeInactive=true 返回 ACTIVE + INACTIVE；删除不返回详情快照，仅可在变更中收到身份标记。恢复后使用原稳定 ID，不按名称另建副本。

### 7.2 首次同步

1. 不带业务筛选读取排程第一页，保存此页 syncCursor 为 S0。
2. 完整分页保存全部当前 ACTIVE 排程，再完整分页保存全部当前 ACTIVE 手机型号。
3. 两类扫描完成后，从最初 S0 读取统一变更流。不要用后续分页/型号列表的其他起点替换 S0。
4. 补偿全部完成后开始常规轮询。扫描期间没出现在某页不代表删除，不可提前清理 CRM 的旧副本。

每次扫描上界外的新 ID，以及扫描过程中发生的改值、删除、停用、恢复，都通过 S0 后变更补入。同步包含当前仍有效的老记录，但不回放接入前业务历史。

### 7.3 增量接口

GET /api/integrations/v3/crm/changes?cursor={cursor}&pageSize=100

cursor 必填，来自 S0 或上一页 nextCursor；pageSize 默认 100，范围 1–100。只接受这两个参数，两个实体共享同一游标流。

```json
{
  "data": {
    "changes": [
      {"changeId": "90001", "entityType": "MOLD_SET", "recordId": "597", "action": "UPSERT", "lifecycleStatus": "ACTIVE", "changedAt": "2026-11-09T02:01:00Z"},
      {"changeId": "90002", "entityType": "PHONE_MODEL", "recordId": "188", "action": "DISABLE", "lifecycleStatus": "INACTIVE", "changedAt": "2026-11-09T02:02:00Z"},
      {"changeId": "90003", "entityType": "MOLD_SET", "recordId": "600", "action": "DELETE", "lifecycleStatus": "DELETED", "changedAt": "2026-11-09T02:03:00Z"}
    ],
    "hasMore": false,
    "nextCursor": "<不透明下一游标>"
  }
}
```

| action | CRM 应处理的含义 |
| --- | --- |
| UPSERT | 新建/修改/依赖/状态/日期推进变化；GET 最新详情并更新副本 |
| DISABLE | 移出当前可用选择，禁止回写，保留已有单据引用 |
| DELETE | 从有效展示移除，禁止回写；不要求删除 CRM 订单等业务历史 |
| ENABLE | 恢复使用；按同 ID GET 最新详情更新 |

建议每 60 秒轮询；hasMore=true 时立即连续翻页。一轮增量固定发布上界，新发布事件进入下一轮。CRM 按 changeId 顺序处理，同一资源不要并行乱序覆盖；整页成功落地后才持久化 nextCursor，失败重读旧游标。

详情返回“当前值”，不是事件时刻快照。处理 UPSERT/ENABLE 时详情可能已停用或 404，应尊重详情；DELETE/DISABLE 之后仍须继续处理可能紧接着的恢复事件。没有变化也应保存返回的 nextCursor。

源事务同步修订资源并捕获最小标记；后台默认每秒发布最多 200 条已提交标记，分配连续对外 changeId。原始 capture 序号不作为消费水位，因此小序号事务晚提交不会被跳过。changedAt 是源捕获时间，不保证按该时间排序，应按 changeId 消费。

### 7.4 保留期与重建

变更默认保留 30 天；同步游标也带过期时间。游标早于清理边界、超期或同步世代已变化时返回 410 CURSOR_EXPIRED；分页令牌过期/跨世代返回 410 PAGE_TOKEN_EXPIRED。签名损坏或主体不符返回 400，不自动跳到最新游标。

410 后重新完成“两类全量 + 新起点补偿”；成功前不批量删除旧副本。全部完成后，才能将本轮未出现的旧记录移出有效使用。扫描失败时禁止清空 CRM 副本。

近期传输标记及成功幂等结果是可靠性所需存储，不提供历史查询接口。

## 8. 错误格式与重试

```json
{
  "error": {"code": "VERSION_CONFLICT", "message": "记录已更新，请重新查询核对后提交", "fields": []},
  "requestId": "示例请求编号"
}
```

| HTTP | error.code | 处理 |
| --- | --- | --- |
| 400 | INVALID_ARGUMENT | 参数、未知/重复字段、日期、无效幂等键、令牌等错误；修正请求 |
| 401 | UNAUTHENTICATED | 凭证无效或服务账号不满足要求；核对认证配置 |
| 403 | FORBIDDEN | scope、内部填写职责或写开关不满足；不能以管理员绕过 |
| 404 | NOT_FOUND | 不存在/已删除；移出有效展示 |
| 409 | RESOURCE_INACTIVE / EXECUTION_RESTRICTED | 停用或暂停限制；核对当前业务状态 |
| 409 | IDEMPOTENCY_KEY_REUSED | 同键不同请求；核对业务操作，不盲目改键重发 |
| 410 | CURSOR_EXPIRED / PAGE_TOKEN_EXPIRED | 按第 7 节重新同步/扫描 |
| 412 | VERSION_CONFLICT | 无字段写入，重新 GET 并人工/业务核对 |
| 422 | BUSINESS_RULE_VIOLATION | 现有业务校验不通过；核对公开原因 |
| 428 | PRECONDITION_REQUIRED | 缺 If-Match |
| 503 | TEMPORARILY_UNAVAILABLE | 接口未启用/未初始化/配置不全或争用；按 Retry-After 退避 |
| 500 | INTERNAL_ERROR | 服务异常；提供 requestId 排查，写请求保留原键重试 |

本模块未实现 429 限流器。网络网关的 404/405/413/429 等错误可能使用网关自己的格式，不属于上述 Controller 业务错误结构。

## 9. 联调前置条件与明确限制

- 需部署本次源码/新迁移，创建专用账号并安全配置凭证、scope，受控初始化捕获，才能对外调用；当前运行服务尚未执行这些步骤。
- 初始化会锁定相关业务表，须维护窗口执行；禁止直接在生产繁忙时调用初始化函数或重置世代。
- ready 表示已初始化，不是发布延迟的健康承诺。须监控 pending 数量/最老延迟、日切进度、错误及数据库锁等待。
- 生产域名、HTTPS、网络白名单、CRM 接入程序及双方联调尚未提供/实施。本版不包含 CRM 侧代码。
- 已验证小规模功能与关键并发场景，未完成生产容量、所有源入口并发矩阵、故障注入和大范围扇出压测，不承诺固定吞吐或秒级送达。
- 部署详情、可运行测试命令及未完成验收项见实施报告，不把源码完成等同于生产上线。
