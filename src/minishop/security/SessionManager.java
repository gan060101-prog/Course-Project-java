package minishop.security;

import com.sun.net.httpserver.HttpExchange;
import minishop.model.User;
import minishop.store.DataStore;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class SessionManager {
    private static final String COOKIE_NAME = "MINISHOP_SESSION";
    private static final Duration SESSION_TTL = Duration.ofHours(8);

    private final Map<String, Session> sessions = new HashMap<>();

    public synchronized String createSession(User user) {
        String token = UUID.randomUUID().toString().replace("-", "");
        sessions.put(token, new Session(user.getId(), Instant.now().plus(SESSION_TTL)));
        return token;
    }

    public synchronized Optional<User> currentUser(HttpExchange exchange, DataStore store) {
        String token = readCookie(exchange);
        if (token == null) {
            return Optional.empty();
        }
        Session session = sessions.get(token);
        if (session == null || session.expiresAt.isBefore(Instant.now())) {
            sessions.remove(token);
            return Optional.empty();
        }
        session.expiresAt = Instant.now().plus(SESSION_TTL);
        return store.findUser(session.userId).filter(User::isActive);
    }

    public synchronized void destroySession(HttpExchange exchange) {
        String token = readCookie(exchange);
        if (token != null) {
            sessions.remove(token);
        }
    }

    public String loginCookie(String token) {
        return COOKIE_NAME + "=" + token + "; Path=/; HttpOnly; SameSite=Lax";
    }

    public String logoutCookie() {
        return COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax";
    }

    private String readCookie(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Cookie");
        if (header == null || header.isBlank()) {
            return null;
        }
        String[] pairs = header.split(";");
        for (String pair : pairs) {
            String[] parts = pair.trim().split("=", 2);
            if (parts.length == 2 && COOKIE_NAME.equals(parts[0])) {
                return parts[1];
            }
        }
        return null;
    }

    private static class Session {
        private final int userId;
        private Instant expiresAt;

        private Session(int userId, Instant expiresAt) {
            this.userId = userId;
            this.expiresAt = expiresAt;
        }
    }
}
