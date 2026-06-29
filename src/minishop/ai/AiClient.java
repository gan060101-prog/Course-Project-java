package minishop.ai;

import minishop.model.AiSettings;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class AiClient {
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public boolean isConfigured(AiSettings settings) {
        return !apiKey(settings).isBlank();
    }

    public String configSummary(AiSettings settings) {
        return "接口: " + apiUrl(settings) + "，模型: " + model(settings);
    }

    public String analyze(String businessSnapshot, String question, AiSettings settings)
            throws IOException, InterruptedException {
        String apiKey = apiKey(settings);
        if (apiKey.isBlank()) {
            throw new IllegalStateException("未配置 DeepSeek API Key，无法调用 AI 接口");
        }
        String prompt = """
                你是“青原优品”的电商产品分析助手。你必须先识别并列出当前平台已有产品，再围绕这些产品分析。
                请用中文输出：
                1. 当前商品清单识别结果，按品类归纳。
                2. 商品结构是否体现青海特色，哪些产品应重点推广。
                3. 库存风险、价格带、订单表现与营销建议。
                4. 给出 3 条可执行的运营动作。
                不要编造数据；如果数据不足，请明确说明。

                后台数据：
                %s

                用户问题：
                %s
                """.formatted(businessSnapshot,
                question == null || question.isBlank() ? "请分析当前青海特色商品的运营情况。" : question);

        String body = """
                {
                  "model": "%s",
                  "messages": [
                    {"role": "system", "content": "你是谨慎、务实的电商产品分析助手，重点分析青海特色产品。"},
                    {"role": "user", "content": "%s"}
                  ],
                  "temperature": 0.3
                }
                """.formatted(jsonEscape(model(settings)), jsonEscape(prompt));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl(settings)))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("AI 接口返回 HTTP " + response.statusCode() + ": " + response.body());
        }
        return extractContent(response.body());
    }

    private String apiKey(AiSettings settings) {
        if (settings != null && !settings.getApiKey().isBlank()) {
            return settings.getApiKey();
        }
        return env("DEEPSEEK_API_KEY", env("LLM_API_KEY", ""));
    }

    private String apiUrl(AiSettings settings) {
        if (settings != null && !settings.getApiUrl().isBlank()) {
            return settings.getApiUrl();
        }
        return env("DEEPSEEK_API_URL", env("LLM_API_URL", "https://api.deepseek.com/chat/completions"));
    }

    private String model(AiSettings settings) {
        if (settings != null && !settings.getModel().isBlank()) {
            return settings.getModel();
        }
        return env("DEEPSEEK_MODEL", env("LLM_MODEL", "deepseek-v4-flash"));
    }

    private String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String extractContent(String json) {
        String marker = "\"content\"";
        int markerIndex = json.indexOf(marker);
        while (markerIndex >= 0) {
            int colonIndex = json.indexOf(':', markerIndex + marker.length());
            if (colonIndex < 0) {
                break;
            }
            int quoteIndex = json.indexOf('"', colonIndex + 1);
            if (quoteIndex < 0) {
                break;
            }
            String value = readJsonString(json, quoteIndex);
            if (!value.isBlank()) {
                return value;
            }
            markerIndex = json.indexOf(marker, quoteIndex + 1);
        }
        return "AI 接口已返回结果，但没有找到 message.content 字段。\n\n原始响应：\n" + json;
    }

    private String readJsonString(String json, int quoteIndex) {
        StringBuilder result = new StringBuilder();
        boolean escaped = false;
        for (int i = quoteIndex + 1; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (escaped) {
                switch (ch) {
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case '"' -> result.append('"');
                    case '\\' -> result.append('\\');
                    case '/' -> result.append('/');
                    case 'u' -> {
                        if (i + 4 < json.length()) {
                            String hex = json.substring(i + 1, i + 5);
                            result.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        }
                    }
                    default -> result.append(ch);
                }
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == '"') {
                return result.toString();
            } else {
                result.append(ch);
            }
        }
        return result.toString();
    }

    private String jsonEscape(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> result.append(ch);
            }
        }
        return result.toString();
    }
}
