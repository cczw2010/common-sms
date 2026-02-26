import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 SMS4J 设计的短信统一工具（单文件版本）。
 *
 * <p>提供能力：
 * <ul>
 *   <li>模板短信单发</li>
 *   <li>模板短信批量发送</li>
 *   <li>模板申请</li>
 *   <li>模板管理（查询、启停、删除）</li>
 * </ul>
 *
 * <p>说明：
 * 1. 为了保持“单文件可直接落地”，这里使用内存模板仓库与发送记录。<br>
 * 2. 通过 {@link Sms4jInvoker} 对接真实 SMS4J 调用，避免业务层直接耦合供应商 SDK。<br>
 * 3. 在 Spring 项目里可将本类注册为 Bean，然后把 invoker 的实现注入进来。
 */
public class Sms4jUnifiedTool {

    private final Sms4jInvoker sms4jInvoker;
    private final Map<String, TemplateMeta> templateStore = new ConcurrentHashMap<>();
    private final Map<String, SendResult> sendAuditStore = new ConcurrentHashMap<>();
    private final AtomicInteger templateSequence = new AtomicInteger(1000);

    public Sms4jUnifiedTool(Sms4jInvoker sms4jInvoker) {
        this.sms4jInvoker = Objects.requireNonNull(sms4jInvoker, "sms4jInvoker 不能为空");
    }

    /**
     * 模板短信单发。
     */
    public SendResult sendTemplateSingle(String templateCode,
                                         String phone,
                                         Map<String, String> templateParams,
                                         String signName,
                                         String channel) {
        validatePhone(phone);
        validateSendCommonParams(signName, channel);
        TemplateMeta template = validateTemplateCanSend(templateCode);

        SendRequest request = new SendRequest(
                templateCode,
                Collections.singletonList(phone),
                defaultIfNull(templateParams),
                signName,
                channel
        );

        SendResult result = sms4jInvoker.send(request);
        return enrichAndAudit(result, template, 1);
    }

    /**
     * 模板短信批量发送。
     */
    public BatchSendResult sendTemplateBatch(String templateCode,
                                             List<String> phones,
                                             Map<String, String> templateParams,
                                             String signName,
                                             String channel) {
        if (phones == null || phones.isEmpty()) {
            throw new IllegalArgumentException("phones 不能为空");
        }
        validateSendCommonParams(signName, channel);
        List<String> deduplicatedPhones = deduplicatePhones(phones);
        for (String phone : deduplicatedPhones) {
            validatePhone(phone);
        }
        TemplateMeta template = validateTemplateCanSend(templateCode);

        BatchSendResult batchResult = new BatchSendResult();
        List<SendResult> details = new ArrayList<>();

        for (String phone : deduplicatedPhones) {
            SendRequest single = new SendRequest(
                    templateCode,
                    Collections.singletonList(phone),
                    defaultIfNull(templateParams),
                    signName,
                    channel
            );
            try {
                SendResult result = sms4jInvoker.send(single);
                details.add(enrichAndAudit(result, template, 1));
            } catch (Exception ex) {
                SendResult failResult = buildFailSendResult(template, ex);
                details.add(enrichAndAudit(failResult, template, 1));
            }
        }

        batchResult.setResults(copySendResultList(details));
        batchResult.setTotal(deduplicatedPhones.size());
        batchResult.setSuccess((int) details.stream().filter(SendResult::isSuccess).count());
        batchResult.setFailed(batchResult.getTotal() - batchResult.getSuccess());
        return batchResult;
    }

    /**
     * 模板申请。
     */
    public TemplateMeta applyTemplate(TemplateApplyRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        if (isBlank(request.getTemplateName()) || isBlank(request.getTemplateContent())) {
            throw new IllegalArgumentException("模板名称和模板内容不能为空");
        }

        String templateCode = "TPL" + templateSequence.incrementAndGet();
        TemplateMeta meta = new TemplateMeta();
        meta.setTemplateCode(templateCode);
        meta.setTemplateName(request.getTemplateName());
        meta.setTemplateContent(request.getTemplateContent());
        meta.setScene(request.getScene());
        meta.setStatus(TemplateStatus.PENDING_APPROVAL);
        meta.setCreatedBy(request.getApplicant());
        meta.setCreatedAt(LocalDateTime.now());
        meta.setUpdatedAt(LocalDateTime.now());

        templateStore.put(templateCode, meta);
        return meta;
    }

    /**
     * 审批模板（模拟模板平台审批）。
     */
    public TemplateMeta approveTemplate(String templateCode, boolean approved, String reason) {
        TemplateMeta meta = getTemplateOrThrow(templateCode);
        if (meta.getStatus() != TemplateStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("只有待审核模板才允许审批，当前状态=" + meta.getStatus());
        }

        meta.setStatus(approved ? TemplateStatus.ENABLED : TemplateStatus.REJECTED);
        meta.setRejectReason(approved ? null : defaultValue(reason, "模板不符合审核规范"));
        meta.setUpdatedAt(LocalDateTime.now());
        templateStore.put(templateCode, meta);
        return meta;
    }

    /**
     * 禁用模板。
     */
    public TemplateMeta disableTemplate(String templateCode) {
        TemplateMeta meta = getTemplateOrThrow(templateCode);
        if (meta.getStatus() == TemplateStatus.DELETED) {
            throw new IllegalStateException("已删除模板无法禁用");
        }
        meta.setStatus(TemplateStatus.DISABLED);
        meta.setUpdatedAt(LocalDateTime.now());
        templateStore.put(templateCode, meta);
        return meta;
    }

    /**
     * 启用模板。
     */
    public TemplateMeta enableTemplate(String templateCode) {
        TemplateMeta meta = getTemplateOrThrow(templateCode);
        if (meta.getStatus() == TemplateStatus.DELETED) {
            throw new IllegalStateException("已删除模板无法启用");
        }
        if (meta.getStatus() == TemplateStatus.PENDING_APPROVAL || meta.getStatus() == TemplateStatus.REJECTED) {
            throw new IllegalStateException("当前状态不可启用：" + meta.getStatus());
        }
        meta.setStatus(TemplateStatus.ENABLED);
        meta.setUpdatedAt(LocalDateTime.now());
        templateStore.put(templateCode, meta);
        return meta;
    }

    /**
     * 删除模板。
     */
    public TemplateMeta deleteTemplate(String templateCode) {
        TemplateMeta meta = getTemplateOrThrow(templateCode);
        meta.setStatus(TemplateStatus.DELETED);
        meta.setUpdatedAt(LocalDateTime.now());
        templateStore.put(templateCode, meta);
        return meta;
    }

    public TemplateMeta getTemplate(String templateCode) {
        return copyTemplateMeta(getTemplateOrThrow(templateCode));
    }

    public List<TemplateMeta> listTemplates() {
        return copyTemplateMetaList(templateStore.values());
    }

    public SendResult querySendResult(String requestId) {
        SendResult result = sendAuditStore.get(requestId);
        if (result == null) {
            throw new IllegalArgumentException("未找到发送记录，requestId=" + requestId);
        }
        return copySendResult(result);
    }

    private SendResult enrichAndAudit(SendResult result, TemplateMeta template, int count) {
        if (isBlank(result.getRequestId())) {
            result.setRequestId(UUID.randomUUID().toString());
        }
        result.setTemplateCode(template.getTemplateCode());
        result.setTemplateName(template.getTemplateName());
        result.setPhoneCount(count);
        result.setSendTime(LocalDateTime.now());
        SendResult toStore = copySendResult(result);
        sendAuditStore.put(toStore.getRequestId(), toStore);
        return copySendResult(toStore);
    }

    private TemplateMeta validateTemplateCanSend(String templateCode) {
        TemplateMeta template = getTemplateOrThrow(templateCode);
        if (template.getStatus() != TemplateStatus.ENABLED) {
            throw new IllegalStateException("模板不可发送，当前状态=" + template.getStatus());
        }
        return template;
    }

    private TemplateMeta getTemplateOrThrow(String templateCode) {
        if (isBlank(templateCode)) {
            throw new IllegalArgumentException("templateCode 不能为空");
        }
        TemplateMeta meta = templateStore.get(templateCode);
        if (meta == null) {
            throw new IllegalArgumentException("模板不存在，templateCode=" + templateCode);
        }
        return meta;
    }

    private void validatePhone(String phone) {
        if (isBlank(phone) || !phone.matches("^1\\d{10}$")) {
            throw new IllegalArgumentException("手机号不合法: " + phone);
        }
    }

    private void validateSendCommonParams(String signName, String channel) {
        if (isBlank(signName)) {
            throw new IllegalArgumentException("signName 不能为空");
        }
        if (isBlank(channel)) {
            throw new IllegalArgumentException("channel 不能为空");
        }
    }

    private List<String> deduplicatePhones(List<String> phones) {
        return new ArrayList<>(new LinkedHashSet<>(phones));
    }

    private SendResult buildFailSendResult(TemplateMeta template, Exception ex) {
        SendResult fail = new SendResult();
        fail.setSuccess(false);
        fail.setProviderCode("INVOKER_EXCEPTION");
        fail.setProviderMessage(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        fail.setTemplateCode(template.getTemplateCode());
        fail.setTemplateName(template.getTemplateName());
        return fail;
    }

    private List<TemplateMeta> copyTemplateMetaList(Iterable<TemplateMeta> metas) {
        List<TemplateMeta> copies = new ArrayList<>();
        for (TemplateMeta meta : metas) {
            copies.add(copyTemplateMeta(meta));
        }
        return copies;
    }

    private List<SendResult> copySendResultList(Iterable<SendResult> sendResults) {
        List<SendResult> copies = new ArrayList<>();
        for (SendResult sendResult : sendResults) {
            copies.add(copySendResult(sendResult));
        }
        return copies;
    }

    private TemplateMeta copyTemplateMeta(TemplateMeta source) {
        if (source == null) {
            return null;
        }
        TemplateMeta copy = new TemplateMeta();
        copy.setTemplateCode(source.getTemplateCode());
        copy.setTemplateName(source.getTemplateName());
        copy.setTemplateContent(source.getTemplateContent());
        copy.setScene(source.getScene());
        copy.setStatus(source.getStatus());
        copy.setRejectReason(source.getRejectReason());
        copy.setCreatedBy(source.getCreatedBy());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private SendResult copySendResult(SendResult source) {
        if (source == null) {
            return null;
        }
        SendResult copy = new SendResult();
        copy.setRequestId(source.getRequestId());
        copy.setSuccess(source.isSuccess());
        copy.setProviderCode(source.getProviderCode());
        copy.setProviderMessage(source.getProviderMessage());
        copy.setTemplateCode(source.getTemplateCode());
        copy.setTemplateName(source.getTemplateName());
        copy.setPhoneCount(source.getPhoneCount());
        copy.setSendTime(source.getSendTime());
        copy.setExt(source.getExt() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source.getExt()));
        return copy;
    }

    private Map<String, String> defaultIfNull(Map<String, String> input) {
        return input == null ? new HashMap<>() : input;
    }

    private String defaultValue(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    private boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }

    /**
     * 真实短信发送适配器：在这里桥接 SMS4J。
     *
     * <p>建议实现方式：
     * - 在实现类中注入 SMS4J 的 Sender；
     * - 根据 channel 选择不同供应商配置；
     * - 将 SMS4J 返回结果映射为 SendResult。
     */
    public interface Sms4jInvoker {
        SendResult send(SendRequest request);
    }

    public static class SendRequest {
        private final String templateCode;
        private final List<String> phones;
        private final Map<String, String> templateParams;
        private final String signName;
        private final String channel;

        public SendRequest(String templateCode,
                           List<String> phones,
                           Map<String, String> templateParams,
                           String signName,
                           String channel) {
            this.templateCode = templateCode;
            this.phones = phones;
            this.templateParams = templateParams;
            this.signName = signName;
            this.channel = channel;
        }

        public String getTemplateCode() {
            return templateCode;
        }

        public List<String> getPhones() {
            return phones;
        }

        public Map<String, String> getTemplateParams() {
            return templateParams;
        }

        public String getSignName() {
            return signName;
        }

        public String getChannel() {
            return channel;
        }
    }

    public static class SendResult {
        private String requestId;
        private boolean success;
        private String providerCode;
        private String providerMessage;
        private String templateCode;
        private String templateName;
        private int phoneCount;
        private LocalDateTime sendTime;
        private Map<String, Object> ext = new LinkedHashMap<>();

        public String getRequestId() {
            return requestId;
        }

        public void setRequestId(String requestId) {
            this.requestId = requestId;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getProviderCode() {
            return providerCode;
        }

        public void setProviderCode(String providerCode) {
            this.providerCode = providerCode;
        }

        public String getProviderMessage() {
            return providerMessage;
        }

        public void setProviderMessage(String providerMessage) {
            this.providerMessage = providerMessage;
        }

        public String getTemplateCode() {
            return templateCode;
        }

        public void setTemplateCode(String templateCode) {
            this.templateCode = templateCode;
        }

        public String getTemplateName() {
            return templateName;
        }

        public void setTemplateName(String templateName) {
            this.templateName = templateName;
        }

        public int getPhoneCount() {
            return phoneCount;
        }

        public void setPhoneCount(int phoneCount) {
            this.phoneCount = phoneCount;
        }

        public LocalDateTime getSendTime() {
            return sendTime;
        }

        public void setSendTime(LocalDateTime sendTime) {
            this.sendTime = sendTime;
        }

        public Map<String, Object> getExt() {
            return ext;
        }

        public void setExt(Map<String, Object> ext) {
            this.ext = ext;
        }
    }

    public static class BatchSendResult {
        private int total;
        private int success;
        private int failed;
        private List<SendResult> results;

        public int getTotal() {
            return total;
        }

        public void setTotal(int total) {
            this.total = total;
        }

        public int getSuccess() {
            return success;
        }

        public void setSuccess(int success) {
            this.success = success;
        }

        public int getFailed() {
            return failed;
        }

        public void setFailed(int failed) {
            this.failed = failed;
        }

        public List<SendResult> getResults() {
            return results;
        }

        public void setResults(List<SendResult> results) {
            this.results = results;
        }
    }

    public static class TemplateApplyRequest {
        private String templateName;
        private String templateContent;
        private String scene;
        private String applicant;

        public String getTemplateName() {
            return templateName;
        }

        public void setTemplateName(String templateName) {
            this.templateName = templateName;
        }

        public String getTemplateContent() {
            return templateContent;
        }

        public void setTemplateContent(String templateContent) {
            this.templateContent = templateContent;
        }

        public String getScene() {
            return scene;
        }

        public void setScene(String scene) {
            this.scene = scene;
        }

        public String getApplicant() {
            return applicant;
        }

        public void setApplicant(String applicant) {
            this.applicant = applicant;
        }
    }

    public static class TemplateMeta {
        private String templateCode;
        private String templateName;
        private String templateContent;
        private String scene;
        private TemplateStatus status;
        private String rejectReason;
        private String createdBy;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public String getTemplateCode() {
            return templateCode;
        }

        public void setTemplateCode(String templateCode) {
            this.templateCode = templateCode;
        }

        public String getTemplateName() {
            return templateName;
        }

        public void setTemplateName(String templateName) {
            this.templateName = templateName;
        }

        public String getTemplateContent() {
            return templateContent;
        }

        public void setTemplateContent(String templateContent) {
            this.templateContent = templateContent;
        }

        public String getScene() {
            return scene;
        }

        public void setScene(String scene) {
            this.scene = scene;
        }

        public TemplateStatus getStatus() {
            return status;
        }

        public void setStatus(TemplateStatus status) {
            this.status = status;
        }

        public String getRejectReason() {
            return rejectReason;
        }

        public void setRejectReason(String rejectReason) {
            this.rejectReason = rejectReason;
        }

        public String getCreatedBy() {
            return createdBy;
        }

        public void setCreatedBy(String createdBy) {
            this.createdBy = createdBy;
        }

        public LocalDateTime getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
        }

        public LocalDateTime getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
        }
    }

    public enum TemplateStatus {
        PENDING_APPROVAL,
        ENABLED,
        DISABLED,
        REJECTED,
        DELETED
    }
}
