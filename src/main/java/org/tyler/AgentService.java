package org.tyler;

import com.openai.client.OpenAIClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AgentService {

    private final OpenAIClient client;
    private final String model;

    public AgentService(OpenAIClient client, @Value("${openai.model:gpt-5.6}") String model) {
        this.client = client;
        this.model = model;
    }

    public String ask(String message) {
        ResponseCreateParams params = ResponseCreateParams.builder()
                .model(model)
                .input(message)
                .build();

        Response response = client.responses().create(params);
        return extractText(response);
    }

    private String extractText(Response response) {
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
