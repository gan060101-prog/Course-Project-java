package minishop.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class FormParser {
    private FormParser() {
    }

    public static Map<String, String> parseQuery(String query) {
        return parsePairs(query == null ? "" : query);
    }

    public static Map<String, String> parseBody(InputStream inputStream) throws IOException {
        byte[] bytes = inputStream.readAllBytes();
        return parsePairs(new String(bytes, StandardCharsets.UTF_8));
    }

    private static Map<String, String> parsePairs(String text) {
        Map<String, String> result = new HashMap<>();
        if (text == null || text.isEmpty()) {
            return result;
        }
        String[] pairs = text.split("&");
        for (String pair : pairs) {
            int separator = pair.indexOf('=');
            String key = separator >= 0 ? pair.substring(0, separator) : pair;
            String value = separator >= 0 ? pair.substring(separator + 1) : "";
            result.put(decode(key), decode(value));
        }
        return result;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
