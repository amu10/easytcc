# easyTcc

> 一个简单、嵌入式、无需单独部署协调服务器的 TCC 分布式事务框架。

## 为什么做 easyTcc？

在需要处理分布式事务时，Seata 是一个成熟且功能丰富的选择，但它通常需要单独部署 Server、配置注册中心和维护额外的基础设施。对于只想使用 TCC 的中小型项目来说，这套体系有时显得偏重，接入和运维成本也比较高。

easyTcc 的初衷很简单：

**如果业务已经能够编写 Try、Confirm、Cancel，能不能只引入一个依赖、加几个注解，就开始使用 TCC？**

因此，easyTcc 将事务协调器直接嵌入 Spring Boot 应用，不需要部署类似 Seata Server 的独立服务。事务日志可以保存在本地文件、Redis 或应用已有的数据库中，让开发者根据项目规模选择最合适的方案。

easyTcc 不打算复制 Seata 的全部能力，也不追求支持所有分布式事务模式。它专注做好一件事：让 TCC 更容易接入、更容易理解、更容易运维。

## 特性

- 无独立 Server，协调器嵌入应用运行
- 注解式 API，尽量减少框架侵入
- 支持标准的 Try、Confirm、Cancel 流程
- Try 全部成功后顺序执行 Confirm
- Try 失败后逆序执行 Cancel
- Confirm/Cancel 失败自动恢复和重试
- 支持 Spring Boot 2.7 和 Spring Boot 3.x
- 核心模块兼容 Java 8，Spring Boot 3 模块使用 Java 17
- 存储层采用 SPI 设计，可扩展自定义实现
- Apache License 2.0

## 当前进度

easyTcc 目前处于早期开发阶段。

| 能力 | 状态 |
|---|---|
| 核心事务状态机 | 已完成 |
| `@EasyTccTransactional` | 已完成 |
| `@EasyTccAction` | 已完成 |
| File 存储 | 已完成 |
| 故障恢复调度 | 已完成 |
| Spring Boot 2.7 Starter | 已完成 |
| Spring Boot 3.x Starter | 已完成 |
| Redis 存储 | 已完成 |
| JDBC/主数据库存储 | 已完成 |
| OpenFeign/HTTP 跨服务传播 | 已完成 |
| Actuator 与 Micrometer | 已完成 |

当前版本适合学习、验证和参与开发，暂不建议直接用于关键生产业务。

生产部署前必须满足的能力、测试矩阵和运维门槛见 [生产就绪计划](docs/production-readiness.md)。

## 工作原理

```text
@EasyTccTransactional 业务入口
             │
             ▼
       创建全局事务 XID
             │
        ┌────┴────┐
        ▼         ▼
   库存 Try    账户 Try
        │         │
        └────┬────┘
             │
      ┌──────┴──────┐
      │             │
  全部成功        任意失败
      │             │
   Confirm        Cancel
      │             │
      └──────┬──────┘
             ▼
       持久化最终状态
```

发起事务的应用同时承担协调者角色。事务和分支状态会写入配置的存储，应用异常退出后，恢复调度器会继续处理未完成的 Confirm 或 Cancel。

## 快速开始

### 1. 引入依赖

Spring Boot 3：

```xml
<dependency>
    <groupId>io.github.easytcc</groupId>
    <artifactId>easy-tcc-spring-boot3-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Spring Boot 2：

```xml
<dependency>
    <groupId>io.github.easytcc</groupId>
    <artifactId>easy-tcc-spring-boot2-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 2. 配置 File 存储

```yaml
easy-tcc:
  file-path: ./data/easy-tcc
  max-retries: 20
  recovery-interval: 10000
  recovery-batch-size: 100
  purge-retention: 604800000  # 终态事务保留 7 天后清理，0 禁用
```

File 存储面向本地开发、测试和单实例应用。分支方法的参数需要实现 `Serializable`。

恢复调度器会按 `purge-retention` 保留期自动清理已到终态（CONFIRMED/CANCELLED）的历史事务，避免存储无限膨胀；清理动作以结构化日志与 `easy_tcc.purged` 指标留痕。三种存储均支持该配置。

生产集群使用 JDBC 存储，并先执行对应数据库建表脚本：

```yaml
easy-tcc:
  storage: jdbc
  max-retries: 20
  recovery-interval: 10000
  recovery-batch-size: 100
```

建表脚本位于 `easy-tcc-storage-jdbc/src/main/resources/io/github/easytcc/storage/jdbc/`，当前提供 MySQL、PostgreSQL 和 H2 版本。JDBC 存储通过版本字段执行乐观锁，并使用带过期时间的恢复租约防止多个实例同时恢复同一事务。

生产业务应在 Try、Confirm、Cancel 的本地数据库事务内调用 `JdbcTccBarrier`，并传入业务使用的同一个 `Connection`。只有 `beginTry`、`beginConfirm` 或 `beginCancel` 返回 `true` 时才执行相应资源操作；返回 `false` 表示重复调用或空操作。该屏障提供幂等、空回滚与防悬挂保护，但不能替代业务资源表自身的约束。

生产集群也可以选择 Redis 存储（同样具备 CAS、恢复租约与执行租约，适用于多实例部署）。所有写操作通过 Lua 脚本原子完成，可恢复事务由有序集合索引支撑：

```yaml
easy-tcc:
  storage: redis
  redis-url: redis://localhost:6379
  max-retries: 20
  recovery-interval: 10000
  recovery-batch-size: 100
  purge-retention: 604800000  # 终态事务保留 7 天后清理，0 禁用
```

Redis 存储以 Hash 保存每个事务（`easy_tcc:tx:{xid}`），以有序集合 `easy_tcc:recoverable`（score 为截止时间或重试时间）支撑恢复扫描，审计事件写入列表 `easy_tcc:audit:{xid}`。若业务资源也在 Redis 中，可在同一 Redis 事务内调用 `RedisTccBarrier` 的 `beginTry/beginConfirm/beginCancel` 获得与 JDBC 屏障一致的三重防护。Redis 存储要求服务端启用 Lua 脚本（默认开启）。

### 3. 声明全局事务

```java
@Service
public class OrderService {

    private final InventoryService inventoryService;
    private final AccountService accountService;

    public OrderService(InventoryService inventoryService,
                        AccountService accountService) {
        this.inventoryService = inventoryService;
        this.accountService = accountService;
    }

    @EasyTccTransactional(name = "create-order", timeout = 30000)
    public void createOrder(String userId, String sku, int quantity, int amount) {
        inventoryService.reserve(sku, quantity);
        accountService.freeze(userId, amount);
    }
}
```

### 4. 声明 TCC 分支

```java
@Service
public class InventoryService {

    @EasyTccAction(
        name = "reserve-inventory",
        confirm = "confirmInventory",
        cancel = "cancelInventory"
    )
    public void reserve(String sku, int quantity) {
        // Try：冻结库存
    }

    public void confirmInventory(String sku, int quantity) {
        // Confirm：正式扣减冻结库存
    }

    public void cancelInventory(String sku, int quantity) {
        // Cancel：释放冻结库存
    }
}
```

Try、Confirm、Cancel 的参数需要保持兼容。业务入口正常完成时，easyTcc 调用 Confirm；任意 Try 抛出异常时，easyTcc 按相反顺序调用 Cancel。

## TCC 业务约束

框架负责事务状态、调用顺序、持久化和失败重试，但无法替业务自动生成正确的补偿逻辑。业务代码需要保证：

- Try 只预留资源，不执行无法撤销的最终操作
- Confirm 可以重复执行
- Cancel 可以重复执行
- Cancel 能安全处理 Try 未成功的空回滚
- Try 能识别事务已经取消，防止悬挂
- Confirm 和 Cancel 最终能够收敛到明确状态

幂等不是可选优化，而是正确使用 TCC 的必要条件。

## 跨服务传播

Starter 会在 Servlet Web 应用中注册入站 Filter，从 `X-Easy-Tcc-Xid` 请求头恢复事务上下文，并在请求结束时清理线程上下文。如果应用使用 OpenFeign，相应拦截器会自动加入 XID 请求头；使用 `RestTemplate` 时，将自动配置提供的 `EasyTccRestTemplateInterceptor` 添加到目标实例。传播的服务必须访问同一个集群事务存储。

异步线程不会隐式继承事务上下文。需要异步执行时，应显式捕获 XID，并通过 `EasyTccPropagation.open(xid)` 创建有界作用域；不要直接在线程池任务之间复用 `ThreadLocal`。

## 人工处置

`TransactionManager.query(xid)` 查询事务，`suspend(xid, reason, operator)` 将非终态事务转入人工介入状态，`retryManually(xid, operator)` 按已经持久化的原始 Confirm/Cancel 决议继续恢复。`auditTrail(xid, limit)` 返回审计记录。JDBC 存储会把开始、暂停、人工重试和最终完成事件写入 `easy_tcc_audit`；管理端点应由应用自行鉴权，不应直接暴露到公网。

## 监控与告警

引入 Spring Boot Actuator 后，Starter 提供 easyTcc 健康检查；存储不可访问时健康状态为 DOWN，事务状态数量和人工介入数量放在详情中。Micrometer 指标前缀为 `easy_tcc`，包括开始、完成、失败、恢复尝试、人工介入累计值，以及带 `status` 标签的事务状态数量。建议对 `MANUAL_INTERVENTION > 0`、失败增长、恢复积压持续增长和存储健康 DOWN 建立告警；阈值应结合业务 SLO 设置，避免把待人工处理直接等同于实例不可用。

## 构建与运行

```shell
mvn clean verify
mvn clean install
mvn -f easy-tcc-samples/easy-tcc-sample-boot3/pom.xml spring-boot:run
```

成功场景：

```shell
curl -X POST "http://localhost:8080/orders?amount=100"
```

将 `amount` 设置为大于 `10000`，可以观察 Try 失败和逆序 Cancel：

```shell
curl -X POST "http://localhost:8080/orders?amount=20000"
```

## 项目模块

```text
easy-tcc-parent
├── easy-tcc-core
├── easy-tcc-storage-file
├── easy-tcc-spring
├── easy-tcc-spring-boot2-starter
├── easy-tcc-spring-boot3-starter
└── easy-tcc-samples
    └── easy-tcc-sample-boot3
```

- `easy-tcc-core`：注解、事务模型、状态机和存储 SPI
- `easy-tcc-storage-file`：本地文件事务日志
- `easy-tcc-spring`：Spring AOP、分支调用和恢复调度
- `easy-tcc-spring-boot2-starter`：Spring Boot 2.7 自动配置
- `easy-tcc-spring-boot3-starter`：Spring Boot 3.x 自动配置
- `easy-tcc-sample-boot3`：完整的使用示例

## 路线图

1. 实现 JDBC 存储，复用应用主数据源
2. 实现 Redis 存储及多实例恢复租约
3. 支持 HTTP、RestTemplate 和 OpenFeign 的 XID 传播
4. 增加分支幂等、防悬挂和空回滚辅助能力
5. 接入 Spring Boot Actuator 和 Micrometer
6. 增加 MySQL、PostgreSQL 和 Redis 的 Testcontainers 集成测试
7. 完善异常查询和人工处理 API

## easyTcc 与 Seata

Seata 是覆盖 AT、TCC、Saga、XA 等模式的完整分布式事务平台，适合需要集中治理和丰富功能的大型系统。

easyTcc 的定位不同：

- 只聚焦 TCC
- 不部署独立协调服务器
- 优先满足轻量项目和简单架构
- 降低学习、接入和运维成本

如果项目需要成熟的集中式事务治理、多种事务模式和大规模生产验证，Seata 仍然是更合适的选择。如果项目只需要一个简单、嵌入式的 TCC 框架，easyTcc 希望成为一个更轻的选择。

## 参与贡献

easyTcc 仍处于早期阶段，欢迎提交 Issue、设计建议和 Pull Request。尤其欢迎共同完善状态机与异常恢复、Redis/JDBC 存储、跨服务事务上下文传播、故障注入测试、文档和示例。

提交代码前请运行：

```shell
mvn clean verify
```

## License

easyTcc 使用 [Apache License 2.0](LICENSE) 开源。
