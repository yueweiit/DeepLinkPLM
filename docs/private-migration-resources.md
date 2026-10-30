# 私有历史迁移原件

四份迁移包含公司真实ECOUNT物料、库存记录、初始化账号凭据或供应商资料，因此不在公开仓库中。它们的文件名和SHA-256见同目录 `private-migration-resources.json`。生产专用供应商迁移的集成测试资料亦不公开；其余迁移及应用业务源码保持发布字节。

公司的生产构建须从已验证的私有发布JAR中恢复这些原件，再运行Maven。可使用：

```sh
python3 scripts/restore_private_migration_resources.py --jar /private/path/server.jar
mvn -f plm-server/pom.xml package -DskipTests
```

工具仅从本地JAR提取这些已知资源并核对SHA-256，不访问数据库，不运行SQL。不要修改历史SQL、改变既有Flyway校验和、执行repair或忽略校验来替代私有原件。生产备份和当前运行JAR继续保留原文件。公开仓库不包含可恢复真实业务记录的数据库副本。
