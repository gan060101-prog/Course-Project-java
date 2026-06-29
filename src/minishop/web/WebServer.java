package minishop.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import minishop.ai.AiClient;
import minishop.model.Order;
import minishop.model.OrderItem;
import minishop.model.OrderStatus;
import minishop.model.Product;
import minishop.model.Role;
import minishop.model.User;
import minishop.security.SessionManager;
import minishop.store.DataStore;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

public class WebServer {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final HttpServer server;
    private final DataStore store;
    private final SessionManager sessions = new SessionManager();
    private final AiClient aiClient = new AiClient();
    private final Path imageDirectory = Path.of("商品图片");
    private final Path backgroundDirectory = Path.of("background");
    private final Path payCodeDirectory = Path.of("pay_code");

    public WebServer(int port, DataStore store) throws IOException {
        this.store = store;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/", this::handle);
        this.server.setExecutor(Executors.newCachedThreadPool());
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    public void start() {
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(method) && "/styles.css".equals(path)) {
                send(exchange, 200, "text/css", styles());
                return;
            }
            if ("GET".equals(method) && "/product-file".equals(path)) {
                serveLocalFile(exchange, imageDirectory);
                return;
            }
            if ("GET".equals(method) && "/background-file".equals(path)) {
                serveLocalFile(exchange, backgroundDirectory);
                return;
            }
            if ("GET".equals(method) && "/pay-code".equals(path)) {
                serveLocalFile(exchange, payCodeDirectory);
                return;
            }
            if ("GET".equals(method) && "/favicon.ico".equals(path)) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if ("GET".equals(method) && "/login".equals(path)) {
                loginPage(exchange, "", "");
                return;
            }
            if ("POST".equals(method) && "/login".equals(path)) {
                doLogin(exchange);
                return;
            }
            if ("GET".equals(method) && "/register".equals(path)) {
                registerPage(exchange, "");
                return;
            }
            if ("POST".equals(method) && "/register".equals(path)) {
                doRegister(exchange);
                return;
            }

            Optional<User> current = sessions.currentUser(exchange, store);
            if (current.isEmpty()) {
                redirect(exchange, "/login");
                return;
            }
            User user = current.get();
            if ("GET".equals(method) && "/logout".equals(path)) {
                sessions.destroySession(exchange);
                exchange.getResponseHeaders().add("Set-Cookie", sessions.logoutCookie());
                redirect(exchange, "/login");
            } else if ("GET".equals(method) && "/".equals(path)) {
                home(exchange, user);
            } else if ("GET".equals(method) && "/products".equals(path)) {
                products(exchange, user);
            } else if ("GET".equals(method) && "/products/detail".equals(path)) {
                productDetail(exchange, user);
            } else if ("GET".equals(method) && "/checkout".equals(path)) {
                require(!user.getRole().isAdmin(), "管理员请在订单管理中创建订单");
                checkout(exchange, user, "");
            } else if ("POST".equals(method) && "/checkout/create".equals(path)) {
                require(!user.getRole().isAdmin(), "管理员请在订单管理中创建订单");
                createCheckoutOrder(exchange, user);
            } else if ("POST".equals(method) && "/checkout/pay".equals(path)) {
                require(!user.getRole().isAdmin(), "管理员请在订单管理中创建订单");
                confirmCheckoutPayment(exchange, user);
            } else if ("POST".equals(method) && "/products/save".equals(path)) {
                require(user.getRole().canManageProducts(), "当前账号没有商品管理权限");
                saveProduct(exchange);
            } else if ("POST".equals(method) && "/products/delete".equals(path)) {
                require(user.getRole().canManageProducts(), "当前账号没有商品管理权限");
                deleteProduct(exchange);
            } else if ("GET".equals(method) && "/orders".equals(path)) {
                require(user.getRole().canManageOrders(), "当前账号没有订单管理权限");
                orders(exchange, user);
            } else if ("GET".equals(method) && "/orders/new".equals(path)) {
                require(user.getRole().canManageOrders(), "当前账号没有订单管理权限");
                newOrder(exchange, user);
            } else if ("POST".equals(method) && "/orders/save".equals(path)) {
                require(user.getRole().canManageOrders(), "当前账号没有订单管理权限");
                saveOrder(exchange);
            } else if ("POST".equals(method) && "/orders/status".equals(path)) {
                require(user.getRole().canManageOrders(), "当前账号没有订单管理权限");
                updateOrderStatus(exchange);
            } else if ("GET".equals(method) && "/marketing".equals(path)) {
                require(user.getRole().isAdmin(), "当前账号没有营销管理权限");
                marketing(exchange, user);
            } else if ("GET".equals(method) && "/ai".equals(path)) {
                require(user.getRole().canUseAi(), "当前账号没有 AI 分析权限");
                aiPage(exchange, user, "", "");
            } else if ("POST".equals(method) && "/ai/analyze".equals(path)) {
                require(user.getRole().canUseAi(), "当前账号没有 AI 分析权限");
                runAi(exchange, user);
            } else if ("POST".equals(method) && "/ai/settings".equals(path)) {
                require(user.getRole().canUseAi(), "当前账号没有 AI 设置权限");
                saveAiSettings(exchange);
            } else if ("GET".equals(method) && "/users".equals(path)) {
                require(user.getRole().canManageUsers(), "当前账号没有账户管理权限");
                users(exchange, user, "");
            } else if ("POST".equals(method) && "/users/save".equals(path)) {
                require(user.getRole().canManageUsers(), "当前账号没有账户管理权限");
                saveManagedUser(exchange, user);
            } else if ("GET".equals(method) && "/settings".equals(path)) {
                settings(exchange, user, "");
            } else if ("POST".equals(method) && "/settings/profile".equals(path)) {
                saveProfile(exchange, user);
            } else {
                sendPage(exchange, user, "页面不存在", "home",
                        "<section class=\"panel\"><h1>页面不存在</h1><p>请从顶部导航进入系统。</p></section>");
            }
        } catch (IllegalArgumentException | IllegalStateException ex) {
            Optional<User> current = sessions.currentUser(exchange, store);
            if (current.isPresent()) {
                sendPage(exchange, current.get(), "操作失败", "home",
                        "<section class=\"panel\"><h1>操作失败</h1><p class=\"error\">"
                                + HtmlPage.escape(ex.getMessage())
                                + "</p><a class=\"button\" href=\"/\">返回首页</a></section>");
            } else {
                loginPage(exchange, ex.getMessage(), "");
            }
        }
    }

    private void require(boolean allowed, String message) {
        if (!allowed) {
            throw new IllegalArgumentException(message);
        }
    }

    private void loginPage(HttpExchange exchange, String error, String message) throws IOException {
        String errorHtml = error == null || error.isBlank() ? "" : "<p class=\"error\">" + HtmlPage.escape(error) + "</p>";
        String messageHtml = message == null || message.isBlank() ? "" : "<p class=\"success\">" + HtmlPage.escape(message) + "</p>";
        String content = """
                <main class="login-card glass">
                    <div class="login-brand">青原优品</div>
                    <h1>后台登录</h1>
                    %s
                    %s
                    <form class="login-form" method="post" action="/login">
                        <label>账号<input name="username" autocomplete="username" required autofocus></label>
                        <label>密码<input name="password" type="password" autocomplete="current-password" required></label>
                        <button class="button primary" type="submit">登录</button>
                    </form>
                    <p class="login-link">还没有账号？<a href="/register">立即注册</a></p>
                </main>
                """.formatted(errorHtml, messageHtml);
        send(exchange, 200, "text/html", HtmlPage.loginLayout("登录", content));
    }

    private void doLogin(HttpExchange exchange) throws IOException {
        Map<String, String> form = FormParser.parseBody(exchange.getRequestBody());
        Optional<User> user = store.authenticate(form.get("username"), form.get("password"));
        if (user.isEmpty()) {
            loginPage(exchange, "账号或密码不正确，或账号已被停用", "");
            return;
        }
        exchange.getResponseHeaders().add("Set-Cookie", sessions.loginCookie(sessions.createSession(user.get())));
        redirect(exchange, "/");
    }

    private void registerPage(HttpExchange exchange, String error) throws IOException {
        String errorHtml = error == null || error.isBlank() ? "" : "<p class=\"error\">" + HtmlPage.escape(error) + "</p>";
        String content = """
                <main class="login-card glass">
                    <div class="login-brand">青原优品</div>
                    <h1>注册账号</h1>
                    %s
                    <form class="login-form" method="post" action="/register">
                        <label>账号<input name="username" autocomplete="username" required autofocus></label>
                        <label>姓名<input name="displayName" required></label>
                        <label>密码<input name="password" type="password" autocomplete="new-password" required></label>
                        <label>确认密码<input name="confirmPassword" type="password" autocomplete="new-password" required></label>
                        <button class="button primary" type="submit">注册</button>
                    </form>
                    <p class="login-link">已有账号？<a href="/login">返回登录</a></p>
                </main>
                """.formatted(errorHtml);
        send(exchange, 200, "text/html", HtmlPage.loginLayout("注册", content));
    }

    private void doRegister(HttpExchange exchange) throws IOException {
        Map<String, String> form = FormParser.parseBody(exchange.getRequestBody());
        String password = form.getOrDefault("password", "");
        if (!password.equals(form.getOrDefault("confirmPassword", ""))) {
            registerPage(exchange, "两次输入的密码不一致");
            return;
        }
        try {
            store.saveUser(null, form.get("username"), form.get("displayName"), password, Role.VIEWER, true);
            loginPage(exchange, "", "注册成功，请使用新账号登录。");
        } catch (IllegalArgumentException ex) {
            registerPage(exchange, ex.getMessage());
        }
    }

    private void home(HttpExchange exchange, User user) throws IOException {
        String stats = user.getRole().isAdmin() ? """
                <section class="stats">
                    %s
                    %s
                    %s
                    %s
                </section>
                """.formatted(
                stat("特色商品", String.valueOf(store.getProductCount())),
                stat("低库存预警", String.valueOf(store.getLowStockCount())),
                stat("订单数量", String.valueOf(store.getOrderCount())),
                stat("有效销售额", HtmlPage.money(store.getSalesAmount()))) : "";
        String content = """
                <section class="hero glass">
                    <div>
                        <p class="eyebrow">青海特色 · 透明简约界面</p>
                        <h1>青原优品运营首页</h1>
                        <p>平台围绕青海特色产品建立商品展示、订单处理、营销分析与 AI 产品洞察能力。当前界面通过顶部居中导航、横向搜索栏、背景图轮播和半透明内容卡片，让后台更轻量，也让牦牛肉、冬虫夏草、三文鱼、黑枸杞、唐卡刺绣、青稞酒、中藏药、藏毯、藜麦等地域商品更有场景感。</p>
                    </div>
                    <a class="button primary" href="/products">查看商品</a>
                </section>
                %s
                <section class="product-grid compact">
                    %s
                </section>
                """.formatted(stats, productCards(store.getActiveProducts(), false, !user.getRole().isAdmin()));
        sendPage(exchange, user, "首页", "home", content);
    }

    private String stat(String label, String value) {
        return "<article class=\"stat glass\"><span>" + HtmlPage.escape(label) + "</span><strong>"
                + HtmlPage.escape(value) + "</strong></article>";
    }

    private void products(HttpExchange exchange, User user) throws IOException {
        Map<String, String> query = FormParser.parseQuery(exchange.getRequestURI().getRawQuery());
        String keyword = query.getOrDefault("q", "");
        Integer editId = intOrNull(query.get("edit"));
        Optional<Product> editing = editId == null ? Optional.empty() : store.findProduct(editId);
        boolean canEdit = user.getRole().canManageProducts();
        String formPanel = "";
        if (canEdit) {
            Product p = editing.orElse(new Product(0, "", "", 0, 0, true));
            formPanel = """
                    <section class="panel glass">
                        <h2>%s</h2>
                        <form class="form-grid" method="post" action="/products/save" enctype="multipart/form-data">
                            <input type="hidden" name="id" value="%d">
                            <label>商品名称<input name="name" required value="%s"></label>
                            <label>分类<input name="category" required value="%s"></label>
                            <label>品牌<input name="brand" value="%s"></label>
                            <label>产地<input name="origin" value="%s"></label>
                            <label>价格<input name="price" type="number" step="0.01" min="0" required value="%.2f"></label>
                            <label>库存<input name="stock" type="number" min="0" required value="%d"></label>
                            <label class="wide">属性<input name="attributes" value="%s"></label>
                            <label class="wide">简介<textarea name="description" rows="4">%s</textarea></label>
                            <label>图片1地址<input name="imageUrl" value="%s"></label>
                            <label>图片2地址<input name="imageUrl2" value="%s"></label>
                            <label>上传图片1<input name="imageFile1" type="file" accept="image/*"></label>
                            <label>上传图片2<input name="imageFile2" type="file" accept="image/*"></label>
                            <div class="form-actions">
                                <button class="button primary" type="submit">保存商品</button>
                                %s
                                <a class="button" href="/products">清空</a>
                            </div>
                        </form>
                    </section>
                    """.formatted(p.getId() == 0 ? "新增商品" : "编辑商品 #" + p.getId(),
                    p.getId(), HtmlPage.attr(p.getName()), HtmlPage.attr(p.getCategory()), HtmlPage.attr(p.getBrand()),
                    HtmlPage.attr(p.getOrigin()), p.getPrice(), p.getStock(), HtmlPage.attr(p.getAttributes()),
                    HtmlPage.escape(p.getDescription()), HtmlPage.attr(p.getImageUrl()), HtmlPage.attr(p.getImageUrl2()),
                    p.getId() == 0 ? "" : "<button class=\"button danger\" type=\"submit\" form=\"delete-product-" + p.getId()
                            + "\" onclick=\"return confirm('确定删除这个商品吗？')\">删除商品</button>");
        }
        String content = """
                <section class="page-title glass">
                    <div><p class="eyebrow">商品</p><h1>青海特色商品</h1></div>
                    <form class="inline-search" method="get" action="/products">
                        <input name="q" placeholder="搜索商品、分类、品牌" value="%s">
                        <button class="button" type="submit">⌕</button>
                    </form>
                </section>
                %s
                <section class="product-grid">
                    %s
                </section>
                """.formatted(HtmlPage.attr(keyword), formPanel,
                productCards(store.searchProducts(keyword), canEdit, !user.getRole().isAdmin()));
        sendPage(exchange, user, "商品", "products", content);
    }

    private String productCards(List<Product> products, boolean canEdit) {
        return productCards(products, canEdit, false);
    }

    private String productCards(List<Product> products, boolean canEdit, boolean canOrder) {
        StringBuilder cards = new StringBuilder();
        for (Product p : products) {
            if (!p.isActive()) {
                continue;
            }
            cards.append("""
                    <article class="product-card glass">
                        <a class="carousel" href="/products/detail?id=%d">
                            <img src="%s" alt="%s">
                            <img src="%s" alt="%s">
                        </a>
                        <div class="product-body">
                            <div class="line-between"><strong>%s</strong><span>%s</span></div>
                            <p>%s</p>
                            <div class="meta"><span>%s</span><span>库存 %d</span></div>
                            <div class="card-actions">
                                <a class="button small" href="/products/detail?id=%d">详情</a>
                                %s
                                %s
                                %s
                            </div>
                            %s
                        </div>
                    </article>
                    """.formatted(p.getId(), HtmlPage.attr(p.getImageUrl()), HtmlPage.attr(p.getName()),
                    HtmlPage.attr(p.getImageUrl2().isBlank() ? p.getImageUrl() : p.getImageUrl2()), HtmlPage.attr(p.getName()),
                    HtmlPage.escape(p.getName()), HtmlPage.money(p.getPrice()), HtmlPage.escape(p.getDescription()),
                    HtmlPage.escape(p.getCategory()), p.getStock(), p.getId(),
                    canEdit ? "<a class=\"button small\" href=\"/products?edit=" + p.getId() + "\">编辑</a>" : "",
                    canEdit && p.isActive()
                            ? "<button class=\"button small danger\" type=\"submit\" form=\"delete-product-" + p.getId()
                                    + "\" onclick=\"return confirm('确定删除这个商品吗？')\">删除</button>"
                            : "",
                    canOrder && p.isActive() && p.getStock() > 0
                            ? "<a class=\"button small primary\" href=\"/checkout?id=" + p.getId() + "\">下单</a>"
                            : "",
                    canEdit && p.isActive()
                            ? "<form id=\"delete-product-" + p.getId() + "\" method=\"post\" action=\"/products/delete\"><input type=\"hidden\" name=\"id\" value=\""
                                    + p.getId() + "\"></form>"
                            : ""));
        }
        return cards.toString();
    }

    private void productDetail(HttpExchange exchange, User user) throws IOException {
        Integer id = intOrNull(FormParser.parseQuery(exchange.getRequestURI().getRawQuery()).get("id"));
        Product p = id == null ? null : store.findProduct(id).orElse(null);
        if (p == null) {
            throw new IllegalArgumentException("商品不存在");
        }
        String content = """
                <section class="detail">
                    <div class="detail-gallery carousel glass">
                        <img src="%s" alt="%s">
                        <img src="%s" alt="%s">
                    </div>
                    <div class="detail-info glass">
                        <p class="eyebrow">%s / %s</p>
                        <h1>%s</h1>
                        <div class="price">%s</div>
                        <p>%s</p>
                        <dl>
                            <dt>产地</dt><dd>%s</dd>
                            <dt>品牌</dt><dd>%s</dd>
                            <dt>商品属性</dt><dd>%s</dd>
                            <dt>库存</dt><dd>%d</dd>
                        </dl>
                        <div class="toolbar">
                            <a class="button" href="/products">返回商品</a>
                            %s
                        </div>
                    </div>
                </section>
                """.formatted(HtmlPage.attr(p.getImageUrl()), HtmlPage.attr(p.getName()),
                HtmlPage.attr(p.getImageUrl2().isBlank() ? p.getImageUrl() : p.getImageUrl2()), HtmlPage.attr(p.getName()),
                HtmlPage.escape(p.getCategory()), HtmlPage.escape(p.getOrigin()), HtmlPage.escape(p.getName()),
                HtmlPage.money(p.getPrice()), HtmlPage.escape(p.getDescription()), HtmlPage.escape(p.getOrigin()),
                HtmlPage.escape(p.getBrand()), HtmlPage.escape(p.getAttributes()), p.getStock(),
                !user.getRole().isAdmin() && p.isActive() && p.getStock() > 0
                        ? "<a class=\"button primary\" href=\"/checkout?id=" + p.getId() + "\">下单付款</a>"
                        : "");
        sendPage(exchange, user, p.getName(), "products", content);
    }

    private void saveProduct(HttpExchange exchange) throws IOException {
        Map<String, String> form = parseProductForm(exchange);
        store.saveProduct(intOrNull(form.get("id")), form.get("name"), form.get("category"), form.get("brand"),
                form.get("origin"), form.get("attributes"), form.get("description"),
                parseDouble(form.get("price")), parseInt(form.get("stock")), form.get("imageUrl"), form.get("imageUrl2"));
        redirect(exchange, "/products");
    }

    private void deleteProduct(HttpExchange exchange) throws IOException {
        store.deactivateProduct(parseInt(FormParser.parseBody(exchange.getRequestBody()).get("id")));
        redirect(exchange, "/products");
    }

    private Map<String, String> parseProductForm(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            return FormParser.parseBody(exchange.getRequestBody());
        }
        MultipartForm form = parseMultipart(exchange, contentType);
        Map<String, String> values = form.fields;
        String productName = values.getOrDefault("name", "product");
        String image1 = saveUploadedProductImage(productName, form.files.get("imageFile1"));
        String image2 = saveUploadedProductImage(productName, form.files.get("imageFile2"));
        if (!image1.isBlank()) {
            values.put("imageUrl", image1);
        }
        if (!image2.isBlank()) {
            values.put("imageUrl2", image2);
        }
        return values;
    }

    private String saveUploadedProductImage(String productName, UploadedFile file) throws IOException {
        if (file == null || file.bytes.length == 0 || file.fileName.isBlank()) {
            return "";
        }
        String extension = imageExtension(file.fileName, file.contentType);
        String base = safeFileBase(productName);
        String fileName = base + "-" + UUID.randomUUID().toString().substring(0, 8) + extension;
        Files.createDirectories(imageDirectory);
        Files.write(imageDirectory.resolve(fileName), file.bytes);
        return "/product-file?name=" + fileName;
    }

    private String imageExtension(String fileName, String contentType) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png") || "image/png".equalsIgnoreCase(contentType)) {
            return ".png";
        }
        if (lower.endsWith(".webp") || "image/webp".equalsIgnoreCase(contentType)) {
            return ".webp";
        }
        if (lower.endsWith(".gif") || "image/gif".equalsIgnoreCase(contentType)) {
            return ".gif";
        }
        return ".jpg";
    }

    private String safeFileBase(String value) {
        String text = value == null || value.isBlank() ? "product" : value.trim();
        String safe = text.replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
        if (safe.isBlank()) {
            return "product";
        }
        return safe.length() > 40 ? safe.substring(0, 40) : safe;
    }

    private MultipartForm parseMultipart(HttpExchange exchange, String contentType) throws IOException {
        String boundary = multipartBoundary(contentType);
        if (boundary.isBlank()) {
            throw new IllegalArgumentException("上传表单缺少 boundary");
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
        MultipartForm result = new MultipartForm();
        String marker = "--" + boundary;
        String[] parts = body.split(java.util.regex.Pattern.quote(marker));
        for (String rawPart : parts) {
            if (rawPart.isBlank() || rawPart.startsWith("--")) {
                continue;
            }
            String part = rawPart;
            if (part.startsWith("\r\n")) {
                part = part.substring(2);
            }
            if (part.endsWith("\r\n")) {
                part = part.substring(0, part.length() - 2);
            }
            int separator = part.indexOf("\r\n\r\n");
            if (separator < 0) {
                continue;
            }
            String headerText = part.substring(0, separator);
            byte[] data = part.substring(separator + 4).getBytes(StandardCharsets.ISO_8859_1);
            Map<String, String> headers = multipartHeaders(headerText);
            String disposition = headers.getOrDefault("content-disposition", "");
            String name = dispositionParameter(disposition, "name");
            String fileName = dispositionParameter(disposition, "filename");
            if (name.isBlank()) {
                continue;
            }
            if (fileName.isBlank()) {
                result.fields.put(name, new String(data, StandardCharsets.UTF_8));
            } else {
                result.files.put(name, new UploadedFile(fileName, headers.getOrDefault("content-type", ""), data));
            }
        }
        return result;
    }

    private String multipartBoundary(String contentType) {
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("boundary=")) {
                String value = trimmed.substring("boundary=".length());
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                    return value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return "";
    }

    private Map<String, String> multipartHeaders(String headerText) {
        Map<String, String> headers = new HashMap<>();
        for (String line : headerText.split("\r\n")) {
            int separator = line.indexOf(':');
            if (separator > 0) {
                headers.put(line.substring(0, separator).trim().toLowerCase(Locale.ROOT),
                        line.substring(separator + 1).trim());
            }
        }
        return headers;
    }

    private String dispositionParameter(String disposition, String parameter) {
        String prefix = parameter + "=\"";
        int start = disposition.indexOf(prefix);
        if (start < 0) {
            return "";
        }
        start += prefix.length();
        int end = disposition.indexOf('"', start);
        if (end < 0) {
            return "";
        }
        return URLDecoder.decode(disposition.substring(start, end), StandardCharsets.UTF_8);
    }

    private void checkout(HttpExchange exchange, User user, String message) throws IOException {
        Integer productId = intOrNull(FormParser.parseQuery(exchange.getRequestURI().getRawQuery()).get("id"));
        Product product = productId == null ? null : store.findProduct(productId).orElse(null);
        if (product == null || !product.isActive()) {
            throw new IllegalArgumentException("商品不存在或已下架");
        }
        String messageHtml = message == null || message.isBlank() ? "" : "<p class=\"success\">" + HtmlPage.escape(message) + "</p>";
        String content = """
                <section class="page-title glass"><div><p class="eyebrow">普通用户 / 下单付款</p><h1>填写订单</h1></div></section>
                <section class="checkout-grid">
                    <article class="panel glass">
                        <h2>订单信息</h2>
                        %s
                        <form class="form-grid" method="post" action="/checkout/create">
                            <input type="hidden" name="productId" value="%d">
                            <label>用户姓名<input name="customerName" required value="%s"></label>
                            <label>联系方式<input name="phone" required placeholder="请输入手机号"></label>
                            <label class="wide">发货地址<input name="address" required placeholder="省、市、区县、详细地址"></label>
                            <label>购买数量<input name="quantity" type="number" min="1" max="%d" required value="1"></label>
                            <label>支付方式<select name="paymentMethod"><option value="WECHAT">微信支付</option><option value="ALIPAY">支付宝支付</option></select></label>
                            <div class="form-actions"><button class="button primary" type="submit">提交订单并查看付款码</button></div>
                        </form>
                    </article>
                    <article class="product-card glass">
                        <div class="carousel">
                            <img src="%s" alt="%s">
                            <img src="%s" alt="%s">
                        </div>
                        <div class="product-body">
                            <div class="line-between"><strong>%s</strong><span>%s</span></div>
                            <p>%s</p>
                            <div class="meta"><span>库存 %d</span><span>%s</span></div>
                        </div>
                    </article>
                </section>
                """.formatted(messageHtml, product.getId(), HtmlPage.attr(user.getDisplayName()), product.getStock(),
                HtmlPage.attr(product.getImageUrl()), HtmlPage.attr(product.getName()),
                HtmlPage.attr(product.getImageUrl2().isBlank() ? product.getImageUrl() : product.getImageUrl2()),
                HtmlPage.attr(product.getName()), HtmlPage.escape(product.getName()), HtmlPage.money(product.getPrice()),
                HtmlPage.escape(product.getDescription()), product.getStock(), HtmlPage.escape(product.getCategory()));
        sendPage(exchange, user, "填写订单", "products", content);
    }

    private void createCheckoutOrder(HttpExchange exchange, User user) throws IOException {
        Map<String, String> form = FormParser.parseBody(exchange.getRequestBody());
        Order order = store.createOrder(form.get("customerName"), form.get("phone"), form.get("address"),
                parseInt(form.get("productId")), parseInt(form.get("quantity")), form.get("paymentMethod"));
        checkoutPayment(exchange, user, order, "订单已创建，当前状态为待处理。扫码完成支付后，请点击下方按钮同步付款状态。");
    }

    private void confirmCheckoutPayment(HttpExchange exchange, User user) throws IOException {
        int orderId = parseInt(FormParser.parseBody(exchange.getRequestBody()).get("orderId"));
        store.updateOrderStatus(orderId, OrderStatus.PAID);
        Order order = store.findOrder(orderId).orElseThrow(() -> new IllegalArgumentException("订单不存在"));
        checkoutPayment(exchange, user, order, "付款状态已同步，管理员订单管理中会显示为已付款。");
    }

    private void checkoutPayment(HttpExchange exchange, User user, Order order, String message) throws IOException {
        String payCode = paymentCodeFile(order.getPaymentMethod());
        String content = """
                <section class="page-title glass"><div><p class="eyebrow">普通用户 / 下单付款</p><h1>付款确认</h1></div></section>
                <section class="checkout-grid">
                    <article class="panel glass">
                        <h2>订单 #%d</h2>
                        <p class="success">%s</p>
                        <dl>
                            <dt>客户姓名</dt><dd>%s</dd>
                            <dt>联系方式</dt><dd>%s</dd>
                            <dt>发货地址</dt><dd>%s</dd>
                            <dt>商品</dt><dd>%s</dd>
                            <dt>金额</dt><dd>%s</dd>
                            <dt>支付方式</dt><dd>%s</dd>
                            <dt>订单状态</dt><dd><span class="tag %s">%s</span></dd>
                        </dl>
                        %s
                    </article>
                    <article class="panel glass pay-panel">
                        <h2>%s</h2>
                        <img class="pay-code" src="/pay-code?name=%s" alt="%s">
                    </article>
                </section>
                """.formatted(order.getId(), HtmlPage.escape(message), HtmlPage.escape(order.getCustomerName()),
                HtmlPage.escape(order.getPhone()), HtmlPage.escape(order.getAddress()), HtmlPage.escape(orderSummary(order)),
                HtmlPage.money(order.getTotal()), HtmlPage.escape(order.getPaymentMethod()),
                order.getStatus() == OrderStatus.PAID ? "ok" : "warn", HtmlPage.escape(order.getStatus().getLabel()),
                order.getStatus() == OrderStatus.PAID ? "<a class=\"button\" href=\"/products\">继续浏览商品</a>" :
                        "<form method=\"post\" action=\"/checkout/pay\"><input type=\"hidden\" name=\"orderId\" value=\""
                                + order.getId()
                                + "\"><button class=\"button primary\" type=\"submit\">我已完成付款</button></form>",
                HtmlPage.escape(order.getPaymentMethod()), HtmlPage.attr(payCode), HtmlPage.attr(order.getPaymentMethod()));
        sendPage(exchange, user, "付款确认", "products", content);
    }

    private String paymentCodeFile(String paymentMethod) {
        return paymentMethod != null && paymentMethod.contains("支付宝") ? "zhifubao.jpg" : "weixin.png";
    }

    private void orders(HttpExchange exchange, User user) throws IOException {
        OrderStatus filter = statusOrNull(FormParser.parseQuery(exchange.getRequestURI().getRawQuery()).get("status"));
        StringBuilder rows = new StringBuilder();
        for (Order order : store.getOrders(filter)) {
            rows.append("<tr><td>#").append(order.getId()).append("</td><td>")
                    .append(HtmlPage.escape(order.getCustomerName())).append("</td><td>")
                    .append(HtmlPage.escape(order.getPhone())).append("</td><td>")
                    .append(HtmlPage.escape(order.getAddress())).append("</td><td>")
                    .append(HtmlPage.escape(orderSummary(order))).append("</td><td>")
                    .append(HtmlPage.money(order.getTotal())).append("</td><td>")
                    .append(HtmlPage.escape(order.getPaymentMethod())).append("</td><td>")
                    .append(HtmlPage.escape(order.getCreatedAt().format(TIME_FORMAT))).append("</td><td>")
                    .append(statusForm(order)).append("</td></tr>");
        }
        String content = """
                <section class="page-title glass">
                    <div><p class="eyebrow">管理员 / 订单管理</p><h1>订单列表</h1></div>
                    <a class="button primary" href="/orders/new">创建模拟订单</a>
                </section>
                <section class="panel toolbar glass">%s</section>
                <section class="panel glass"><table><thead><tr><th>订单号</th><th>客户</th><th>联系方式</th><th>收货地址</th><th>商品</th><th>金额</th><th>支付方式</th><th>时间</th><th>状态</th></tr></thead><tbody>%s</tbody></table></section>
                """.formatted(filterButtons(filter), rows);
        sendPage(exchange, user, "订单管理", "orders", content);
    }

    private String filterButtons(OrderStatus current) {
        StringBuilder buttons = new StringBuilder("<a class=\"button " + (current == null ? "active-filter" : "") + "\" href=\"/orders\">全部</a>");
        for (OrderStatus status : OrderStatus.values()) {
            buttons.append("<a class=\"button ").append(current == status ? "active-filter" : "")
                    .append("\" href=\"/orders?status=").append(status.name()).append("\">")
                    .append(HtmlPage.escape(status.getLabel())).append("</a>");
        }
        return buttons.toString();
    }

    private String statusForm(Order order) {
        StringBuilder options = new StringBuilder();
        for (OrderStatus status : OrderStatus.values()) {
            options.append("<option value=\"").append(status.name()).append("\"")
                    .append(order.getStatus() == status ? " selected" : "").append(">")
                    .append(HtmlPage.escape(status.getLabel())).append("</option>");
        }
        return "<form class=\"inline-form\" method=\"post\" action=\"/orders/status\"><input type=\"hidden\" name=\"id\" value=\""
                + order.getId() + "\"><select name=\"status\">" + options
                + "</select><button class=\"button small\" type=\"submit\">更新</button></form>";
    }

    private String orderSummary(Order order) {
        StringBuilder summary = new StringBuilder();
        for (OrderItem item : order.getItems()) {
            if (summary.length() > 0) {
                summary.append("，");
            }
            summary.append(item.getProductName()).append(" x ").append(item.getQuantity());
        }
        return summary.toString();
    }

    private void newOrder(HttpExchange exchange, User user) throws IOException {
        StringBuilder options = new StringBuilder();
        for (Product product : store.getActiveProducts()) {
            options.append("<option value=\"").append(product.getId()).append("\">")
                    .append(HtmlPage.escape(product.getName())).append(" / 库存 ")
                    .append(product.getStock()).append(" / ").append(HtmlPage.money(product.getPrice())).append("</option>");
        }
        String content = """
                <section class="page-title glass"><div><p class="eyebrow">订单管理</p><h1>创建模拟订单</h1></div></section>
                <section class="panel glass">
                    <form class="form-grid" method="post" action="/orders/save">
                        <label>客户姓名<input name="customerName" required value="顾晓晨"></label>
                        <label>电话<input name="phone" required value="13997118888"></label>
                        <label class="wide">收货地址<input name="address" required value="青海省西宁市城西区胜利路 28 号"></label>
                        <label class="wide">商品<select name="productId">%s</select></label>
                        <label>数量<input name="quantity" type="number" min="1" required value="1"></label>
                        <div class="form-actions"><button class="button primary" type="submit">提交订单</button></div>
                    </form>
                </section>
                """.formatted(options);
        sendPage(exchange, user, "创建订单", "orders", content);
    }

    private void saveOrder(HttpExchange exchange) throws IOException {
        Map<String, String> form = FormParser.parseBody(exchange.getRequestBody());
        store.createOrder(form.get("customerName"), form.get("phone"), form.get("address"),
                parseInt(form.get("productId")), parseInt(form.get("quantity")));
        redirect(exchange, "/orders");
    }

    private void updateOrderStatus(HttpExchange exchange) throws IOException {
        Map<String, String> form = FormParser.parseBody(exchange.getRequestBody());
        store.updateOrderStatus(parseInt(form.get("id")), OrderStatus.valueOf(form.get("status")));
        redirect(exchange, "/orders");
    }

    private void marketing(HttpExchange exchange, User user) throws IOException {
        Map<String, Double> revenue = categoryRevenueFromOrders();
        String content = """
                <section class="page-title glass"><div><p class="eyebrow">管理员 / 营销管理</p><h1>结果分析图</h1></div></section>
                <section class="chart-grid">
                    <article class="panel chart-panel glass">
                        <h2>订单销售额占比圆饼图</h2>
                        <div class="pie-wrap">
                            <div class="pie" style="background: conic-gradient(%s);"></div>
                            <div class="pie-labels">%s</div>
                        </div>
                        <div class="legend">%s</div>
                    </article>
                    <article class="panel chart-panel glass">
                        <h2>订单品类销售额条形图</h2>
                        <div class="bars">%s</div>
                    </article>
                </section>
                <section class="panel glass"><h2>实时联动说明</h2><p>营销分析图直接读取订单管理中的当前订单数据，只有未取消订单会计入统计。管理员在订单管理中新增订单、取消订单或恢复订单后，饼图百分比和条形图金额会随页面刷新实时变化，用于观察青海特色商品在不同品类中的销售贡献。</p></section>
                """.formatted(pieSegments(revenue), pieCenterLabel(revenue), legend(revenue), barChart(revenue));
        sendPage(exchange, user, "营销管理", "marketing", content);
    }

    private Map<String, Double> categoryRevenueFromOrders() {
        Map<String, Double> revenue = new HashMap<>();
        for (Order order : store.getOrders(null)) {
            if (order.getStatus() == OrderStatus.CANCELLED) {
                continue;
            }
            for (OrderItem item : order.getItems()) {
                Product product = store.findProduct(item.getProductId()).orElse(null);
                String category = product == null ? "其他" : product.getCategory();
                revenue.put(category, revenue.getOrDefault(category, 0.0) + item.getSubtotal());
            }
        }
        return revenue;
    }

    private String pieSegments(Map<String, Double> values) {
        String[] colors = { "#2f80ed", "#16a085", "#f2b705", "#7c3aed", "#ef6c00", "#64748b", "#0f766e" };
        double total = values.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0) {
            return "#cbd5e1 0% 100%";
        }
        double start = 0;
        StringBuilder css = new StringBuilder();
        int index = 0;
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            double end = start + entry.getValue() * 100.0 / total;
            if (css.length() > 0) {
                css.append(",");
            }
            css.append(colors[index++ % colors.length]).append(" ")
                    .append(String.format(Locale.ROOT, "%.2f%% %.2f%%", start, end));
            start = end;
        }
        return css.toString();
    }

    private String pieCenterLabel(Map<String, Double> values) {
        double total = values.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0) {
            return "<span>暂无订单</span>";
        }
        StringBuilder html = new StringBuilder();
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            double percent = entry.getValue() * 100.0 / total;
            html.append("<span>").append(HtmlPage.escape(entry.getKey())).append(" ")
                    .append(String.format(Locale.ROOT, "%.1f%%", percent)).append("</span>");
        }
        return html.toString();
    }

    private String legend(Map<String, Double> values) {
        double total = values.values().stream().mapToDouble(Double::doubleValue).sum();
        StringBuilder html = new StringBuilder();
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            double percent = total <= 0 ? 0 : entry.getValue() * 100.0 / total;
            html.append("<span>").append(HtmlPage.escape(entry.getKey())).append("：")
                    .append(HtmlPage.money(entry.getValue())).append(" / ")
                    .append(String.format(Locale.ROOT, "%.1f%%", percent)).append("</span>");
        }
        return html.toString();
    }

    private String barChart(Map<String, Double> values) {
        double max = values.values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
        StringBuilder html = new StringBuilder();
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            int width = (int) Math.max(12, entry.getValue() * 100 / max);
            html.append("<div class=\"bar-row\"><span>").append(HtmlPage.escape(entry.getKey()))
                    .append("</span><i style=\"width:").append(width).append("%\"></i><em>")
                    .append(HtmlPage.money(entry.getValue())).append("</em></div>");
        }
        return html.toString();
    }

    private void aiPage(HttpExchange exchange, User user, String question, String result) throws IOException {
        var settings = store.getAiSettings();
        String config = aiClient.isConfigured(settings)
                ? "<span class=\"tag ok\">已配置</span> " + HtmlPage.escape(aiClient.configSummary(settings))
                : "<span class=\"tag warn\">未配置</span> 请填写 DeepSeek API Key。";
        String resultHtml = result == null || result.isBlank() ? "" : """
                <section class="panel glass"><h2>AI 产品分析结果</h2><div class="ai-result">%s</div></section>
                """.formatted(HtmlPage.nl2br(result));
        String content = """
                <section class="page-title glass"><div><p class="eyebrow">管理员 / AI 分析</p><h1>产品分析助手</h1></div></section>
                <section class="panel glass">
                    <h2>DeepSeek API 设置</h2>
                    <p class="muted">%s</p>
                    <form class="form-grid" method="post" action="/ai/settings">
                        <label class="wide">API Key<input name="apiKey" type="password" placeholder="%s"></label>
                        <label class="wide">接口地址<input name="apiUrl" value="%s"></label>
                        <label>模型<input name="model" value="%s"></label>
                        <div class="form-actions"><button class="button primary" type="submit">保存设置</button></div>
                    </form>
                </section>
                <section class="panel glass">
                    <h2>AI 交互</h2>
                    <form class="ai-form" method="post" action="/ai/analyze">
                        <label>分析问题<textarea name="question" rows="5" placeholder="例如：识别当前产品并分析哪些青海特色产品适合组合营销。">%s</textarea></label>
                        <button class="button primary" type="submit">开始分析</button>
                    </form>
                </section>
                %s
                """.formatted(config,
                settings.getApiKey().isBlank() ? "填写 DeepSeek API Key" : "已保存，留空表示不修改",
                HtmlPage.attr(settings.getApiUrl()), HtmlPage.attr(settings.getModel()), HtmlPage.escape(question),
                resultHtml);
        sendPage(exchange, user, "AI 分析", "ai", content);
    }

    private void runAi(HttpExchange exchange, User user) throws IOException {
        String question = FormParser.parseBody(exchange.getRequestBody()).getOrDefault("question", "");
        String result;
        try {
            result = aiClient.analyze(store.buildBusinessSnapshot(), question, store.getAiSettings());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            result = "AI 分析被中断，请稍后重试。";
        } catch (RuntimeException | IOException ex) {
            result = "AI 调用失败：" + ex.getMessage();
        }
        aiPage(exchange, user, question, result);
    }

    private void saveAiSettings(HttpExchange exchange) throws IOException {
        Map<String, String> form = FormParser.parseBody(exchange.getRequestBody());
        store.saveAiSettings(form.get("apiKey"), form.get("apiUrl"), form.get("model"));
        redirect(exchange, "/ai");
    }

    private void users(HttpExchange exchange, User currentUser, String message) throws IOException {
        Integer editId = intOrNull(FormParser.parseQuery(exchange.getRequestURI().getRawQuery()).get("edit"));
        Optional<User> editing = editId == null ? Optional.empty() : store.findUser(editId);
        User formUser = editing.orElse(new User(0, "", "", "", Role.VIEWER, true));
        String messageHtml = message == null || message.isBlank() ? "" : "<p class=\"success\">" + HtmlPage.escape(message) + "</p>";
        StringBuilder rows = new StringBuilder();
        for (User account : store.getUsers()) {
            rows.append("<tr><td>#").append(account.getId()).append("</td><td>")
                    .append(HtmlPage.escape(account.getUsername())).append("</td><td>")
                    .append(HtmlPage.escape(account.getDisplayName())).append("</td><td>")
                    .append(HtmlPage.escape(account.getRole().isAdmin() ? "管理员" : "普通用户")).append("</td><td>")
                    .append(account.isActive() ? "<span class=\"tag ok\">启用</span>" : "<span class=\"tag muted\">停用</span>")
                    .append("</td><td><a class=\"button small\" href=\"/users?edit=").append(account.getId())
                    .append("\">编辑</a></td></tr>");
        }
        String content = """
                <section class="page-title glass">
                    <div><p class="eyebrow">管理员 / 账户管理</p><h1>账户信息与权限</h1></div>
                    <a class="button" href="/users">新增账户</a>
                </section>
                <section class="settings-grid">
                    <article class="panel glass">
                        <h2>%s</h2>
                        %s
                        <form class="form-grid" method="post" action="/users/save">
                            <input type="hidden" name="id" value="%d">
                            <label>登录账号<input name="username" required value="%s"></label>
                            <label>显示姓名<input name="displayName" required value="%s"></label>
                            <label>权限角色<select name="role">%s</select></label>
                            <label>账号状态<select name="active">%s</select></label>
                            <label class="wide">登录密码<input name="password" type="password" placeholder="%s"></label>
                            <div class="form-actions"><button class="button primary" type="submit">保存账户</button></div>
                        </form>
                    </article>
                    <article class="panel glass">
                        <h2>管理说明</h2>
                        <p>账户管理用于维护后台登录身份和基础权限。系统只区分管理员与普通用户：管理员可以处理商品、订单、营销图表、AI 分析和账户信息；普通用户只保留首页、商品和系统设置入口，适合浏览商品资料、调整个人信息和进行基础查看。</p>
                        <p>修改密码时，编辑已有账户可以留空，表示保持原密码不变；新增账户必须填写密码。为了避免把自己锁在系统外，当前登录管理员不能把自己的账号改成普通用户，也不能停用自己的账号。</p>
                    </article>
                </section>
                <section class="panel glass">
                    <h2>账户列表</h2>
                    <table><thead><tr><th>ID</th><th>登录账号</th><th>显示姓名</th><th>角色</th><th>状态</th><th>操作</th></tr></thead><tbody>%s</tbody></table>
                </section>
                """.formatted(
                formUser.getId() == 0 ? "新增账户" : "编辑账户 #" + formUser.getId(),
                messageHtml,
                formUser.getId(),
                HtmlPage.attr(formUser.getUsername()),
                HtmlPage.attr(formUser.getDisplayName()),
                roleOptions(formUser.getRole()),
                activeOptions(formUser.isActive()),
                formUser.getId() == 0 ? "新增账户必须填写密码" : "留空表示不修改密码",
                rows);
        sendPage(exchange, currentUser, "账户管理", "users", content);
    }

    private void saveManagedUser(HttpExchange exchange, User currentUser) throws IOException {
        Map<String, String> form = FormParser.parseBody(exchange.getRequestBody());
        Integer id = intOrNull(form.get("id"));
        Role role = "ADMIN".equals(form.get("role")) ? Role.ADMIN : Role.VIEWER;
        boolean active = Boolean.parseBoolean(form.getOrDefault("active", "false"));
        if (id != null && id == currentUser.getId() && (!active || role != Role.ADMIN)) {
            throw new IllegalArgumentException("不能停用当前登录管理员，也不能把自己改成普通用户");
        }
        store.saveUser(id, form.get("username"), form.get("displayName"), form.get("password"), role, active);
        redirect(exchange, "/users");
    }

    private String roleOptions(Role role) {
        boolean admin = role != null && role.isAdmin();
        return "<option value=\"ADMIN\"" + (admin ? " selected" : "") + ">管理员</option>"
                + "<option value=\"VIEWER\"" + (!admin ? " selected" : "") + ">普通用户</option>";
    }

    private String activeOptions(boolean active) {
        return "<option value=\"true\"" + (active ? " selected" : "") + ">启用</option>"
                + "<option value=\"false\"" + (!active ? " selected" : "") + ">停用</option>";
    }

    private void settings(HttpExchange exchange, User user, String message) throws IOException {
        String messageHtml = message == null || message.isBlank() ? "" : "<p class=\"success\">" + HtmlPage.escape(message) + "</p>";
        String content = """
                <section class="page-title glass"><div><p class="eyebrow">系统设置</p><h1>个人与界面设置</h1></div></section>
                <section class="settings-grid">
                    <article class="panel glass">
                        <h2>界面偏好</h2>
                        <p>这些设置保存在浏览器本地，用于快速调整页面明暗、字体大小和整体风格。切换后立即生效，不影响其他用户账号。</p>
                        <div class="form-grid">
                            <label>明暗模式<select id="themeSelect"><option value="light">明亮</option><option value="dark">暗色</option></select></label>
                            <label>字体大小<select id="fontSelect"><option value="normal">标准</option><option value="large">偏大</option><option value="compact">紧凑</option></select></label>
                            <label>界面风格<select id="styleSelect"><option value="plain">简约透明</option><option value="soft">柔和圆角</option><option value="sharp">清晰线框</option></select></label>
                        </div>
                    </article>
                    <article class="panel glass">
                        <h2>用户信息</h2>
                        %s
                        <form class="form-grid" method="post" action="/settings/profile">
                            <label>用户名<input name="username" required value="%s"></label>
                            <label>显示姓名<input name="displayName" required value="%s"></label>
                            <label class="wide">新密码<input name="password" type="password" placeholder="留空表示不修改密码"></label>
                            <div class="form-actions"><button class="button primary" type="submit">保存用户信息</button></div>
                        </form>
                    </article>
                </section>
                <section class="panel glass platform-summary">
                    <dl class="settings-list">
                        <dt>平台名称</dt><dd>青原优品</dd>
                        <dt>平台定位</dt><dd>青海特色产品电商后台</dd>
                    </dl>
                </section>
                <script>
                (function () {
                    var theme = document.getElementById("themeSelect");
                    var font = document.getElementById("fontSelect");
                    var style = document.getElementById("styleSelect");
                    theme.value = localStorage.getItem("qy-theme") || "light";
                    font.value = localStorage.getItem("qy-font") || "normal";
                    style.value = localStorage.getItem("qy-style") || "plain";
                    function apply() {
                        localStorage.setItem("qy-theme", theme.value);
                        localStorage.setItem("qy-font", font.value);
                        localStorage.setItem("qy-style", style.value);
                        document.documentElement.dataset.theme = theme.value;
                        document.documentElement.dataset.font = font.value;
                        document.documentElement.dataset.style = style.value;
                    }
                    theme.onchange = apply;
                    font.onchange = apply;
                    style.onchange = apply;
                    apply();
                })();
                </script>
                """.formatted(messageHtml, HtmlPage.attr(user.getUsername()), HtmlPage.attr(user.getDisplayName()));
        sendPage(exchange, user, "系统设置", "settings", content);
    }

    private void saveProfile(HttpExchange exchange, User user) throws IOException {
        Map<String, String> form = FormParser.parseBody(exchange.getRequestBody());
        store.updateUserProfile(user.getId(), form.get("username"), form.get("displayName"), form.get("password"));
        settings(exchange, store.findUser(user.getId()).orElse(user), "用户信息已保存。");
    }

    private void serveLocalFile(HttpExchange exchange, Path directory) throws IOException {
        String fileName = FormParser.parseQuery(exchange.getRequestURI().getRawQuery()).get("name");
        if (fileName == null || fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        Path file = directory.resolve(fileName).normalize();
        if (!Files.exists(file)) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        sendBytes(exchange, 200, contentType(fileName), Files.readAllBytes(file));
    }

    private String contentType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/jpeg";
    }

    private void sendPage(HttpExchange exchange, User user, String title, String active, String content) throws IOException {
        send(exchange, 200, "text/html", HtmlPage.layout(title, active, user, content));
    }

    private void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        sendBytes(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private void sendBytes(HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        if (contentType.startsWith("text/")) {
            headers.set("Content-Type", contentType + "; charset=utf-8");
        } else {
            headers.set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    private Integer intOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseInt(value);
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("数字格式不正确");
        }
    }

    private double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("金额格式不正确");
        }
    }

    private OrderStatus statusOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return OrderStatus.valueOf(value);
    }

    private static class MultipartForm {
        private final Map<String, String> fields = new HashMap<>();
        private final Map<String, UploadedFile> files = new HashMap<>();
    }

    private static class UploadedFile {
        private final String fileName;
        private final String contentType;
        private final byte[] bytes;

        private UploadedFile(String fileName, String contentType, byte[] bytes) {
            this.fileName = fileName == null ? "" : fileName;
            this.contentType = contentType == null ? "" : contentType;
            this.bytes = bytes == null ? new byte[0] : bytes;
        }
    }

    private String styles() {
        return """
                * { box-sizing: border-box; }
                html[data-theme="dark"] { color-scheme: dark; }
                html[data-font="large"] body { font-size: 18px; }
                html[data-font="compact"] body { font-size: 14px; }
                body { margin: 0; min-height: 100vh; font-family: "Microsoft YaHei", "Segoe UI", sans-serif; color: #20242c; background: #eef3fb; }
                html[data-theme="dark"] body { color: #e5edf7; background: #0f172a; }
                a { color: inherit; }
                .background-rotator { position: fixed; inset: 0; z-index: -2; overflow: hidden; background: #e2e8f0; }
                .background-rotator::after { content: ""; position: absolute; inset: 0; background: rgba(245,247,251,.58); backdrop-filter: blur(1px); }
                html[data-theme="dark"] .background-rotator::after { background: rgba(15,23,42,.70); }
                .background-rotator span { position: absolute; inset: 0; background-size: cover; background-position: center; opacity: 0; animation: bgFade 25s infinite; }
                .background-rotator span:nth-child(1) { background-image: url('/background-file?name=background_1.jpg'); animation-delay: 0s; }
                .background-rotator span:nth-child(2) { background-image: url('/background-file?name=background_2.jpg'); animation-delay: 5s; }
                .background-rotator span:nth-child(3) { background-image: url('/background-file?name=background_3.jpg'); animation-delay: 10s; }
                .background-rotator span:nth-child(4) { background-image: url('/background-file?name=background_4.jpg'); animation-delay: 15s; }
                .background-rotator span:nth-child(5) { background-image: url('/background-file?name=background_5.jpg'); animation-delay: 20s; }
                @keyframes bgFade { 0%, 18% { opacity: 1; } 24%, 100% { opacity: 0; } }
                .glass { background: rgba(255,255,255,.82); border: 1px solid rgba(226,232,240,.88); box-shadow: 0 10px 30px rgba(24,32,45,.08); backdrop-filter: blur(10px); }
                html[data-theme="dark"] .glass { background: rgba(15,23,42,.78); border-color: rgba(148,163,184,.32); }
                html[data-style="soft"] .glass, html[data-style="soft"] .button, html[data-style="soft"] input, html[data-style="soft"] select, html[data-style="soft"] textarea { border-radius: 16px; }
                html[data-style="sharp"] .glass, html[data-style="sharp"] .button, html[data-style="sharp"] input, html[data-style="sharp"] select, html[data-style="sharp"] textarea { border-radius: 2px; box-shadow: none; }
                .top-shell { height: 72px; display: grid; grid-template-columns: 190px minmax(280px, 560px) auto; gap: 24px; align-items: center; justify-content: center; padding: 0 28px; background: rgba(255,255,255,.86); border-bottom: 1px solid rgba(226,232,240,.86); backdrop-filter: blur(12px); }
                html[data-theme="dark"] .top-shell, html[data-theme="dark"] .main-nav { background: rgba(15,23,42,.84); border-color: rgba(148,163,184,.32); }
                .brand { font-size: 24px; font-weight: 900; color: #0f766e; text-decoration: none; }
                .global-search { display: flex; align-items: center; gap: 8px; width: 100%; }
                .global-search input, input, select, textarea { min-height: 38px; border: 1px solid #cbd5e1; border-radius: 8px; padding: 8px 10px; font: inherit; background: rgba(255,255,255,.92); color: inherit; width: 100%; }
                html[data-theme="dark"] .global-search input, html[data-theme="dark"] input, html[data-theme="dark"] select, html[data-theme="dark"] textarea { background: rgba(15,23,42,.8); border-color: rgba(148,163,184,.4); }
                .global-search button { width: 46px; min-height: 38px; border: 0; border-radius: 8px; background: #0f766e; color: #fff; font-size: 20px; line-height: 1; }
                .account { display: flex; gap: 10px; align-items: center; white-space: nowrap; }
                .account span, .muted { color: #637083; }
                html[data-theme="dark"] .account span, html[data-theme="dark"] .muted { color: #a7b4c6; }
                .account a { text-decoration: none; color: #0f766e; font-weight: 700; }
                .main-nav { display: flex; justify-content: center; align-items: center; flex-wrap: wrap; gap: 10px; padding: 12px 28px; background: rgba(255,255,255,.82); border-bottom: 1px solid rgba(226,232,240,.86); backdrop-filter: blur(12px); }
                .main-nav a { display: inline-flex; min-width: 110px; min-height: 38px; align-items: center; justify-content: center; padding: 0 16px; border-radius: 8px; text-decoration: none; color: #334155; font-weight: 800; }
                html[data-theme="dark"] .main-nav a { color: #dbe7f3; }
                .main-nav a.active, .main-nav a:hover { background: #e8f7ef; color: #0f766e; }
                .main { max-width: 1280px; margin: 0 auto; padding: 28px; }
                .hero, .page-title { display: flex; justify-content: space-between; align-items: center; gap: 18px; margin-bottom: 22px; padding: 24px; border-radius: 8px; }
                .eyebrow { margin: 0 0 6px; color: #0f766e; font-weight: 900; }
                h1 { margin: 0; font-size: 30px; }
                h2 { margin: 0 0 16px; font-size: 19px; }
                p { line-height: 1.9; }
                .stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; margin-bottom: 20px; }
                .stat, .panel, .product-card { border-radius: 8px; }
                .stat { padding: 18px; display: grid; gap: 8px; }
                .stat span { color: #637083; }
                .stat strong { font-size: 26px; }
                .panel { padding: 20px; margin-bottom: 18px; overflow-x: auto; }
                .button { display: inline-flex; align-items: center; justify-content: center; min-height: 38px; border: 1px solid #cbd5e1; border-radius: 8px; padding: 0 14px; background: rgba(255,255,255,.86); text-decoration: none; cursor: pointer; font: inherit; white-space: nowrap; color: inherit; }
                .button.primary { background: #0f766e; color: #fff; border-color: #0f766e; }
                .button.danger { background: #fff1f2; color: #b42318; border-color: #fecdd3; }
                .button.small { min-height: 32px; padding: 0 10px; font-size: 14px; }
                .button.active-filter { background: #e8f7ef; border-color: #0f766e; color: #0f766e; }
                .inline-search, .toolbar, .inline-form, .card-actions, .form-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
                .form-grid { display: grid; grid-template-columns: repeat(2, minmax(220px, 1fr)); gap: 14px; align-items: end; }
                label { display: grid; gap: 8px; color: inherit; font-weight: 700; }
                .wide { grid-column: 1 / -1; }
                .product-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
                .product-card { overflow: hidden; }
                .carousel { position: relative; display: block; height: 210px; overflow: hidden; background: #edf1f7; }
                .carousel img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; animation: fadeA 6s infinite; }
                .carousel img:nth-child(2) { animation-name: fadeB; }
                @keyframes fadeA { 0%, 45% { opacity: 1; } 50%, 95% { opacity: 0; } 100% { opacity: 1; } }
                @keyframes fadeB { 0%, 45% { opacity: 0; } 50%, 95% { opacity: 1; } 100% { opacity: 0; } }
                .product-body { padding: 16px; display: grid; gap: 10px; }
                .line-between { display: flex; justify-content: space-between; gap: 12px; align-items: start; }
                .line-between span, .price { color: #b45309; font-weight: 900; }
                .meta { display: flex; gap: 8px; flex-wrap: wrap; color: #637083; font-size: 14px; }
                .detail { display: grid; grid-template-columns: minmax(360px, 1fr) 1fr; gap: 24px; }
                .detail-gallery { height: 430px; border-radius: 8px; }
                .detail-info { padding: 24px; border-radius: 8px; }
                .price { font-size: 30px; margin: 14px 0; }
                dl { display: grid; grid-template-columns: 96px 1fr; gap: 10px; }
                dt { color: #637083; }
                table { width: 100%; border-collapse: collapse; }
                th, td { border-bottom: 1px solid rgba(203,213,225,.7); padding: 12px 10px; text-align: left; vertical-align: middle; }
                th { color: #637083; font-size: 14px; }
                .tag { display: inline-flex; align-items: center; min-height: 24px; border-radius: 999px; padding: 0 9px; font-size: 12px; font-weight: 700; }
                .tag.ok { background: #e8f7ef; color: #16794c; }
                .tag.warn { background: #fff3cd; color: #946200; }
                .tag.muted { background: #edf1f7; color: #526070; }
                .chart-grid { display: grid; grid-template-columns: 420px 1fr; gap: 18px; }
                .pie-wrap { display: grid; grid-template-columns: 230px 1fr; gap: 16px; align-items: center; }
                .pie { width: 220px; height: 220px; border-radius: 50%; border: 10px solid rgba(248,250,252,.85); }
                .pie-labels, .legend { display: flex; flex-wrap: wrap; gap: 8px; color: #637083; }
                .pie-labels { display: grid; align-content: center; }
                .bars { display: grid; gap: 12px; }
                .bar-row { display: grid; grid-template-columns: 120px 1fr 92px; gap: 10px; align-items: center; }
                .bar-row i { display: block; height: 18px; border-radius: 999px; background: #0f766e; }
                .ai-result { line-height: 1.8; }
                .settings-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; }
                .checkout-grid { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(320px, .85fr); gap: 18px; align-items: start; }
                .pay-panel { display: grid; justify-items: center; gap: 14px; }
                .pay-code { width: min(320px, 100%); aspect-ratio: 1 / 1; object-fit: contain; border-radius: 8px; background: #fff; border: 1px solid rgba(203,213,225,.8); padding: 10px; }
                .settings-list { display: grid; grid-template-columns: 120px 1fr; gap: 12px; }
                .platform-summary { max-width: 560px; margin-left: auto; margin-right: auto; }
                .site-footer { max-width: 1280px; margin: 20px auto 0; padding: 18px 28px 28px; color: #637083; display: flex; gap: 18px; flex-wrap: wrap; justify-content: center; border-top: 1px solid rgba(226,232,240,.7); }
                .login-body { display: grid; place-items: center; min-height: 100vh; padding: 24px; }
                .login-card { width: min(420px, 100%); border-radius: 8px; padding: 28px; }
                .login-brand { color: #0f766e; font-size: 28px; font-weight: 900; margin-bottom: 12px; }
                .login-form, .ai-form { display: grid; gap: 16px; }
                .login-link { margin: 18px 0 0; color: #637083; text-align: center; }
                .login-link a { color: #0f766e; font-weight: 700; text-decoration: none; }
                .error { color: #b42318; font-weight: 700; }
                .success { color: #16794c; font-weight: 700; }
                @media (max-width: 980px) {
                    .top-shell { height: auto; grid-template-columns: 1fr; padding: 16px; }
                    .main { padding: 18px; }
                    .stats, .product-grid, .chart-grid, .detail, .settings-grid, .checkout-grid, .pie-wrap { grid-template-columns: 1fr; }
                    .form-grid { grid-template-columns: 1fr; }
                }
                """;
    }
}
