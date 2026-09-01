package org.tyler;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputRefusal;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseUsage;

import java.util.Optional;

public class Main {

    public static void main(String[] args) {

        OpenAIClient client = OpenAIOkHttpClient.fromEnv();

        ResponseCreateParams params = ResponseCreateParams.builder()
                .model("gpt-5.6")
                .input("你是我的第一个 Java AI Agent。请用一句话向我打招呼。")
                .build();

        Response response = client.responses().create(params);

        // ============================================================
        // Response 解析教程：像剥洋葱一样，一层一层取出模型真正说的话
        // ============================================================

        // 第 1 层：元数据 —— response 自身的标识和状态（不含正文）
        String id = response.id();                            // 本次响应的唯一 ID
        Optional<ResponseStatus> status = response.status();  // completed / failed / in_progress ...
        Optional<ResponseUsage> usage = response.usage();     // token 消耗统计

        // 第 2 层：output —— 模型返回的内容项列表 List<ResponseOutputItem>
        // 一个 output 项可能是：助手消息 message / 工具调用 function_call / 思考过程 reasoning ...
        for (ResponseOutputItem item : response.output()) {

            // 第 3 层：message —— 从 output 项里取出「助手消息」
            Optional<ResponseOutputMessage> message = item.message();
            if (message.isEmpty()) {
                continue;   // 这一项不是消息（例如是工具调用），跳过
            }

            // 第 4 层：content —— 一条消息里的内容片段列表
            // 每个片段可能是：output_text（文本）/ output_refusal（拒绝）/ reasoning_text（思考）
            for (ResponseOutputMessage.Content content : message.get().content()) {

                // 情况 A：正常的文本输出
                Optional<ResponseOutputText> text = content.outputText();
                if (text.isPresent()) {
                    System.out.println("模型说：" + text.get().text());
                }

                // 情况 B：模型拒绝回答（触发了安全策略）
                Optional<ResponseOutputRefusal> refusal = content.refusal();
                if (refusal.isPresent()) {
                    System.out.println("模型拒绝：" + refusal.get().refusal());
                }
            }
        }

        // 第 5 层（可选）：token 消耗，方便你了解成本
        usage.ifPresent(u -> System.out.println(
                "消耗 token —— 输入：" + u.inputTokens()
                        + "，输出：" + u.outputTokens()
                        + "，总计：" + u.totalTokens()));
    }

    // ============================================================
    // 可复用方法：从任意 Response 里把文本抽出来拼成一段话
    // ============================================================
    static String extractText(Response response) {
        StringBuilder sb = new StringBuilder();

        for (ResponseOutputItem item : response.output()) {
            item.message().ifPresent(msg -> {

                for (ResponseOutputMessage.Content content : msg.content()) {
                    content.outputText().ifPresent(t -> sb.append(t.text()));
                    content.refusal().ifPresent(r -> sb.append(r.refusal()));
                }

            });
        }

        return sb.toString();
    }
}
