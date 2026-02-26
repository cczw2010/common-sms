import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 最终实际使用示例（可直接看流程）。
 *
 * <p>在真实 SpringBoot 项目中：
 * 1. 将 SMS4J 的发送 Bean 注入为 sms4jSender；
 * 2. 使用 Sms4jDromaraInvoker 构造 Sms4jUnifiedTool；
 * 3. 业务仅调用 Sms4jUnifiedTool。
 */
public class Sms4jUsageDemo {

    public static void main(String[] args) {
        // 这里用 FakeSms4jSender 模拟 SMS4J 对象。
        // 生产中替换为 dromara/SMS4J 提供的真实发送对象（如 SmsBlend Bean）。
        Object sms4jSender = new FakeSms4jSender();

        Sms4jUnifiedTool.Sms4jInvoker invoker = new Sms4jDromaraInvoker(sms4jSender, "sendMessage");
        Sms4jUnifiedTool tool = new Sms4jUnifiedTool(invoker);

        // 1) 模板申请 + 审批
        Sms4jUnifiedTool.TemplateApplyRequest apply = new Sms4jUnifiedTool.TemplateApplyRequest();
        apply.setTemplateName("登录验证码");
        apply.setTemplateContent("您的验证码为${code}，5分钟内有效");
        apply.setScene("LOGIN");
        apply.setApplicant("demo");

        Sms4jUnifiedTool.TemplateMeta template = tool.applyTemplate(apply);
        tool.approveTemplate(template.getTemplateCode(), true, null);

        // 2) 单发
        Map<String, String> params = new HashMap<>();
        params.put("code", "123456");
        Sms4jUnifiedTool.SendResult single = tool.sendTemplateSingle(
                template.getTemplateCode(),
                "13800138000",
                params,
                "演示签名",
                "aliyun"
        );
        System.out.println("single result = " + single.getProviderCode() + ", success=" + single.isSuccess());

        // 3) 批量
        Sms4jUnifiedTool.BatchSendResult batch = tool.sendTemplateBatch(
                template.getTemplateCode(),
                Arrays.asList("13800138000", "13900139000", "13800138000"),
                params,
                "演示签名",
                "aliyun"
        );
        System.out.println("batch total=" + batch.getTotal() + ", success=" + batch.getSuccess() + ", failed=" + batch.getFailed());
    }

    /**
     * 模拟 SMS4J 发送对象（方法签名与反射调用对齐）。
     */
    public static class FakeSms4jSender {
        public String sendMessage(String phone, String templateId, Map<String, String> params, String signName) {
            return "phone=" + phone + ",template=" + templateId + ",sign=" + signName + ",params=" + params;
        }
    }
}
