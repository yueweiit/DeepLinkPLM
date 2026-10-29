# DeepLinkPLM

面向手机配件业务的产品研发与供应商协作系统。使用 Vue 3 / TypeScript、Spring Boot / Java 17 和 PostgreSQL，保留 Python 手机市场资料采集子服务。

包含新品研发、开模申请、模具排程、BOM与工艺、供应商产品目录、样品选择与收样借还，以及销售订单与模具成本统计。手机型号与产品主数据统一复用，编码规则见 `coding-rules.md`。

## 源码布局

- `plm-web/`：Vue前端、页面和组件测试。
- `plm-server/`：Spring Boot接口、领域服务、Flyway迁移与测试。
- `services/phone-watch/`：Python采集及内部API。
- `services/dingtalk-mold-sync/`：钉钉连接器适配代码。
- `scripts/`：主数据导出、维护及本地验收工具。

## 构建

后端使用 Java 17 和 Maven。公司生产构建前，先按 `docs/private-migration-resources.md` 从私有发布JAR还原三份含私有业务资料或初始化账号的历史迁移；公开仓库不包含这些数据。

```sh
mvn -f plm-server/pom.xml package -DskipTests
```

前端使用 Node.js 和仓库锁定的依赖：

```sh
cd plm-web
npm ci
npm run type-check
npm run test:run
npm run build
```

本地编排见 `docker-compose.yml`；各环境通过环境变量提供自己的数据库、文件目录和集成配置。Python依赖位于 `services/phone-watch/requirements.txt`，配置样例见该目录 `.env.example`。

真实钉钉写入、中央编码和外部派发使用各自的显式开关；开发与测试应使用本地数据库和模拟外部边界。

## 发布来源

本仓库从已冻结和验收的源码建立，未复制旧Git历史。发布源码清单与验证结果见 `docs/release-source.md`。生产凭证、数据库备份、业务Excel/PDF、运行图片与附件、日志和缓存均不在仓库中。
