package minishop.model;

import java.io.Serializable;

public class AiSettings implements Serializable {
    private static final long serialVersionUID = 1L;

    private String apiKey;
    private String apiUrl;
    private String model;

    public AiSettings() {
        this("", "https://api.deepseek.com/chat/completions", "deepseek-v4-flash");
    }

    public AiSettings(String apiKey, String apiUrl, String model) {
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.model = model;
    }

    public String getApiKey() {
        return apiKey == null ? "" : apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiUrl() {
        return apiUrl == null || apiUrl.isBlank() ? "https://api.deepseek.com/chat/completions" : apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getModel() {
        return model == null || model.isBlank() ? "deepseek-v4-flash" : model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
