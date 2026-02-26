# common-sms

基于 [SMS4J](https://github.com/dromara/SMS4J) 的**通用短信封装（单文件版）**示例，核心实现位于 `Sms4jUnifiedTool.java`。

## 1. 功能范围

当前封装提供以下统一接口能力：

- 模板短信单发
- 模板短信批量发送
- 模板申请
- 模板审批（通过/驳回）
- 模板管理（启用、禁用、删除、查询、列表）
- 发送记录审计查询

> 说明：为了保持“单文件可直接落地”，模板与发送记录使用内存存储。

---

## 2. 设计说明

`Sms4jUnifiedTool` 并不直接依赖具体厂商 SDK，而是通过 `Sms4jInvoker` 作为桥接接口：

- 业务层只调用 `Sms4jUnifiedTool`
- `Sms4jInvoker` 负责在内部对接 SMS4J 的发送实现
- 后续切换渠道/厂商时，业务接口不变

这样可以避免业务代码到处散落 SMS4J 调用逻辑。

---

## 3. 快速使用

### 3.1 初始化工具

```java
Sms4jUnifiedTool.Sms4jInvoker invoker = request -> {
    Sms4jUnifiedTool.SendResult result = new Sms4jUnifiedTool.SendResult();
    result.setSuccess(true);
    result.setProviderCode("OK");
    result.setProviderMessage("mock success");
    return result;
};

Sms4jUnifiedTool tool = new Sms4jUnifiedTool(invoker);
```

### 3.2 模板申请与审批

```java
Sms4jUnifiedTool.TemplateApplyRequest apply = new Sms4jUnifiedTool.TemplateApplyRequest();
apply.setTemplateName("登录验证码");
apply.setTemplateContent("您的验证码为${code}，5分钟内有效");
apply.setScene("LOGIN");
apply.setApplicant("system");

Sms4jUnifiedTool.TemplateMeta tpl = tool.applyTemplate(apply);
tool.approveTemplate(tpl.getTemplateCode(), true, null);
```

### 3.3 单发

```java
Map<String, String> params = new HashMap<>();
params.put("code", "123456");

Sms4jUnifiedTool.SendResult result = tool.sendTemplateSingle(
        "TPL1001",
        "13800138000",
        params,
        "示例签名",
        "aliyun"
);
```

### 3.4 批量发送

```java
List<String> phones = Arrays.asList("13800138000", "13900139000");
Map<String, String> params = Map.of("code", "888888");

Sms4jUnifiedTool.BatchSendResult batch = tool.sendTemplateBatch(
        "TPL1001",
        phones,
        params,
        "示例签名",
        "aliyun"
);
```

---

## 4. 核心接口说明

### 4.1 发送相关

- `sendTemplateSingle(...)`：模板单发
- `sendTemplateBatch(...)`：模板批量发送
- `querySendResult(requestId)`：查询发送审计结果

### 4.2 模板相关

- `applyTemplate(request)`：申请模板
- `approveTemplate(templateCode, approved, reason)`：审批模板
- `enableTemplate(templateCode)` / `disableTemplate(templateCode)`：模板启停
- `deleteTemplate(templateCode)`：删除模板
- `getTemplate(templateCode)` / `listTemplates()`：模板查询

### 4.3 状态枚举

`TemplateStatus`：

- `PENDING_APPROVAL`
- `ENABLED`
- `DISABLED`
- `REJECTED`
- `DELETED`

---

## 5. 与真实 SMS4J 集成建议

在实际项目中，建议：

1. 新建 `Sms4jInvoker` 实现类，在 `send(SendRequest request)` 方法中调用 SMS4J。
2. 根据 `channel` 路由不同配置（如阿里云、腾讯云等）。
3. 将 `SendResult` 统一映射为：`success/providerCode/providerMessage/requestId`。
4. 内存存储替换为数据库或缓存（模板、发送审计）。
5. 在外围增加限流、重试、告警与监控埋点。

---

## 6. 当前限制

- 当前为示例级封装：不含持久化、不含分布式锁、不含 MQ 异步化。
- 手机号校验规则为大陆手机号简化规则：`^1\d{10}$`。
- 批量发送默认按“逐条调用 invoker”实现，便于后续替换为供应商批量 API。

