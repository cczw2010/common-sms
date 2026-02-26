import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 dromara/SMS4J 的实际发送实现（反射桥接版）。
 *
 * <p>说明：
 * 1. 该实现直接接收 SMS4J 的发送对象实例（通常是 SmsBlend Bean）。
 * 2. 为了适配不同 SMS4J 版本方法签名，使用反射调用，避免当前仓库引入依赖后编译失败。
 * 3. 生产中建议改成“直接 import SMS4J 类型”的强类型实现。
 */
public class Sms4jDromaraInvoker implements Sms4jUnifiedTool.Sms4jInvoker {

    private final Object sms4jSender;
    private final String sendMethodName;

    /**
     * @param sms4jSender   SMS4J 的发送实例（例如 Spring 容器里的 SmsBlend）
     * @param sendMethodName 发送方法名，默认常见为 sendMessage
     */
    public Sms4jDromaraInvoker(Object sms4jSender, String sendMethodName) {
        this.sms4jSender = Objects.requireNonNull(sms4jSender, "sms4jSender 不能为空");
        this.sendMethodName = isBlank(sendMethodName) ? "sendMessage" : sendMethodName;
    }

    @Override
    public Sms4jUnifiedTool.SendResult send(Sms4jUnifiedTool.SendRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        if (request.getPhones() == null || request.getPhones().isEmpty()) {
            throw new IllegalArgumentException("phones 不能为空");
        }

        try {
            Object response = invokeSms4j(request);
            return mapResponse(response);
        } catch (InvocationTargetException ex) {
            Throwable target = ex.getTargetException() == null ? ex : ex.getTargetException();
            return fail("SMS4J_INVOKE_ERROR", target.getMessage());
        } catch (Exception ex) {
            return fail("SMS4J_INVOKE_ERROR", ex.getMessage());
        }
    }

    private Object invokeSms4j(Sms4jUnifiedTool.SendRequest request) throws Exception {
        Method method = findMethod(sms4jSender.getClass(), sendMethodName,
                String.class, String.class, Map.class, String.class);
        if (method != null) {
            return method.invoke(sms4jSender,
                    request.getPhones().get(0),
                    request.getTemplateCode(),
                    new LinkedHashMap<>(request.getTemplateParams()),
                    request.getSignName());
        }

        method = findMethod(sms4jSender.getClass(), sendMethodName,
                String.class, String.class, Map.class);
        if (method != null) {
            return method.invoke(sms4jSender,
                    request.getPhones().get(0),
                    request.getTemplateCode(),
                    new LinkedHashMap<>(request.getTemplateParams()));
        }

        throw new NoSuchMethodException("未找到可用 SMS4J 发送方法，请检查 sendMethodName 或 SMS4J 版本");
    }

    private Sms4jUnifiedTool.SendResult mapResponse(Object response) {
        Sms4jUnifiedTool.SendResult result = new Sms4jUnifiedTool.SendResult();
        result.setSuccess(true);
        result.setProviderCode("OK");
        result.setProviderMessage("SMS4J invoke success");
        result.getExt().put("rawResponse", response == null ? null : String.valueOf(response));
        return result;
    }

    private Sms4jUnifiedTool.SendResult fail(String code, String message) {
        Sms4jUnifiedTool.SendResult result = new Sms4jUnifiedTool.SendResult();
        result.setSuccess(false);
        result.setProviderCode(code);
        result.setProviderMessage(isBlank(message) ? "unknown sms4j error" : message);
        return result;
    }

    private Method findMethod(Class<?> type, String name, Class<?>... paramTypes) {
        try {
            return type.getMethod(name, paramTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
