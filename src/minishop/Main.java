package minishop;

import minishop.store.DataStore;
import minishop.web.WebServer;

import java.io.IOException;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws Exception {
        int preferredPort = readPort(args);
        DataStore store = new DataStore(Path.of("data"));
        WebServer server = createServer(preferredPort, store);
        server.start();
        System.out.println("青原优品后台已启动: http://localhost:" + server.getPort());
        System.out.println("按 Ctrl+C 停止服务。");
    }

    private static int readPort(String[] args) {
        if (args.length > 0) {
            return Integer.parseInt(args[0]);
        }
        String property = System.getProperty("port");
        return property == null || property.isBlank() ? 8080 : Integer.parseInt(property);
    }

    private static WebServer createServer(int preferredPort, DataStore store) throws IOException {
        IOException lastError = null;
        for (int port = preferredPort; port < preferredPort + 10; port++) {
            try {
                return new WebServer(port, store);
            } catch (IOException ex) {
                lastError = ex;
            }
        }
        throw lastError == null ? new IOException("无法启动服务器") : lastError;
    }
}
