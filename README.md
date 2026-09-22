# easyTcc

easyTcc 是一个嵌入 Spring Boot 应用、无需部署独立协调服务器的轻量级 TCC 事务框架。

当前 `0.1.0-SNAPSHOT` 已包含核心状态机、注解式事务、File 存储、故障恢复调度器，以及 Spring Boot 2.7/3.x Starter。Redis、JDBC 和跨服务事务将在下一阶段实现。

## 快速开始

Spring Boot 3：

```xml
<dependency>
  <groupId>io.github.easytcc</groupId>
  <artifactId>easy-tcc-spring-boot3-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Spring Boot 2：将 artifactId 改为 `easy-tcc-spring-boot2-starter`。

```java
@EasyTccTransactional(name = "create-order")
public void createOrder(String userId, String sku) {
    inventoryService.reserve(sku, 1);
    accountService.freeze(userId, 100);
}

@EasyTccAction(name = "reserve-inventory", confirm = "confirm", cancel = "cancel")
public void reserve(String sku, int quantity) {
    // Try：冻结库存
}

public void confirm(String sku, int quantity) {
    // Confirm：扣减冻结库存
}

public void cancel(String sku, int quantity) {
    // Cancel：释放冻结库存
}
```

Confirm 和 Cancel 必须幂等，并能处理重复调用。File 存储要求分支方法参数实现 `Serializable`，只适合开发、测试和单实例部署。

## 构建与运行示例

```shell
mvn clean verify
mvn -pl easy-tcc-samples/easy-tcc-sample-boot3 -am spring-boot:run
curl -X POST "http://localhost:8080/orders?amount=100"
```

将 `amount` 设置为大于 `10000` 可观察逆序 Cancel。

## 模块

- `easy-tcc-core`：注解、事务模型、状态机与存储 SPI
- `easy-tcc-storage-file`：本地文件持久化
- `easy-tcc-spring`：AOP、Spring 分支调用和恢复调度
- `easy-tcc-spring-boot2-starter`：Spring Boot 2.7 自动配置
- `easy-tcc-spring-boot3-starter`：Spring Boot 3.x 自动配置
- `easy-tcc-samples/easy-tcc-sample-boot3`：可运行示例

## License

Apache License 2.0
