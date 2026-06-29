package minishop.store;

import minishop.model.AiSettings;
import minishop.model.Order;
import minishop.model.OrderItem;
import minishop.model.OrderStatus;
import minishop.model.Product;
import minishop.model.Role;
import minishop.model.User;
import minishop.security.PasswordUtil;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public class DataStore {
    private final Path dataFile;
    private Database database;

    public DataStore(Path dataDirectory) {
        this.dataFile = dataDirectory.resolve("shop-data.bin");
        load();
        ensureDefaults();
    }

    public synchronized List<Product> getProducts() {
        ArrayList<Product> copy = new ArrayList<>(database.products);
        copy.sort(Comparator.comparing(Product::isActive).reversed().thenComparing(Product::getId));
        return copy;
    }

    public synchronized List<Product> getActiveProducts() {
        ArrayList<Product> result = new ArrayList<>();
        for (Product product : database.products) {
            if (product.isActive()) {
                result.add(product);
            }
        }
        result.sort(Comparator.comparing(Product::getId));
        return result;
    }

    public synchronized List<Product> searchProducts(String keyword) {
        String key = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        ArrayList<Product> result = new ArrayList<>();
        for (Product product : getProducts()) {
            if (key.isEmpty()
                    || product.getName().toLowerCase(Locale.ROOT).contains(key)
                    || product.getCategory().toLowerCase(Locale.ROOT).contains(key)
                    || product.getBrand().toLowerCase(Locale.ROOT).contains(key)
                    || product.getOrigin().toLowerCase(Locale.ROOT).contains(key)) {
                result.add(product);
            }
        }
        return result;
    }

    public synchronized Optional<Product> findProduct(int id) {
        return database.products.stream().filter(product -> product.getId() == id).findFirst();
    }

    public synchronized void saveProduct(Integer id, String name, String category, double price, int stock) {
        saveProduct(id, name, category, "", "", "", "", price, stock, "", "");
    }

    public synchronized void saveProduct(Integer id, String name, String category, double price, int stock,
            String imageUrl) {
        saveProduct(id, name, category, "", "", "", "", price, stock, imageUrl, "");
    }

    public synchronized void saveProduct(Integer id, String name, String category, String brand, String origin,
            String attributes, String description, double price, int stock, String imageUrl, String imageUrl2) {
        validateProduct(name, category, price, stock);
        Product product = id == null || id == 0
                ? new Product(database.nextProductId++, name.trim(), category.trim(), brand, origin, attributes,
                        description, price, stock, true, imageUrl, imageUrl2)
                : findProduct(id).orElseThrow(() -> new IllegalArgumentException("商品不存在"));
        product.setName(name.trim());
        product.setCategory(category.trim());
        product.setBrand(clean(brand));
        product.setOrigin(clean(origin));
        product.setAttributes(clean(attributes));
        product.setDescription(clean(description));
        product.setPrice(price);
        product.setStock(stock);
        product.setImageUrl(clean(imageUrl));
        product.setImageUrl2(clean(imageUrl2));
        product.setActive(true);
        if (id == null || id == 0) {
            database.products.add(product);
        }
        persist();
    }

    public synchronized void deactivateProduct(int id) {
        Product product = findProduct(id).orElseThrow(() -> new IllegalArgumentException("商品不存在"));
        product.setActive(false);
        persist();
    }

    public synchronized List<String> categories() {
        return unique(Product::getCategory);
    }

    public synchronized List<String> brands() {
        return unique(product -> product.getBrand().isBlank() ? "青原优品自营" : product.getBrand());
    }

    public synchronized List<String> attributes() {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Product product : getActiveProducts()) {
            for (String part : product.getAttributes().split("；|;|,|，")) {
                if (!part.trim().isEmpty()) {
                    values.add(part.trim());
                }
            }
        }
        return new ArrayList<>(values);
    }

    private List<String> unique(ValueReader reader) {
        Set<String> values = new LinkedHashSet<>();
        for (Product product : getActiveProducts()) {
            String value = reader.read(product);
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        }
        return new ArrayList<>(values);
    }

    public synchronized List<Order> getOrders(OrderStatus filter) {
        ArrayList<Order> copy = new ArrayList<>();
        for (Order order : database.orders) {
            if (filter == null || order.getStatus() == filter) {
                copy.add(order);
            }
        }
        copy.sort(Comparator.comparing(Order::getCreatedAt).reversed());
        return copy;
    }

    public synchronized Order createOrder(String customerName, String phone, String address, int productId,
            int quantity) {
        return createOrder(customerName, phone, address, productId, quantity, "后台录入");
    }

    public synchronized Order createOrder(String customerName, String phone, String address, int productId,
            int quantity, String paymentMethod) {
        if (isBlank(customerName) || isBlank(phone) || isBlank(address)) {
            throw new IllegalArgumentException("客户姓名、电话和地址不能为空");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("购买数量必须大于 0");
        }
        Product product = findProduct(productId).orElseThrow(() -> new IllegalArgumentException("商品不存在"));
        if (!product.isActive()) {
            throw new IllegalArgumentException("商品已下架，不能下单");
        }
        if (product.getStock() < quantity) {
            throw new IllegalArgumentException("库存不足，当前库存为 " + product.getStock());
        }
        product.setStock(product.getStock() - quantity);
        OrderItem item = new OrderItem(product.getId(), product.getName(), product.getPrice(), quantity);
        Order order = new Order(database.nextOrderId++, customerName.trim(), phone.trim(), address.trim(),
                LocalDateTime.now(), OrderStatus.PENDING, List.of(item), cleanPaymentMethod(paymentMethod));
        database.orders.add(order);
        persist();
        return order;
    }

    public synchronized void updateOrderStatus(int orderId, OrderStatus newStatus) {
        Order order = findOrder(orderId).orElseThrow(() -> new IllegalArgumentException("订单不存在"));
        OrderStatus oldStatus = order.getStatus();
        if (oldStatus == newStatus) {
            return;
        }
        if (newStatus == OrderStatus.CANCELLED && oldStatus != OrderStatus.CANCELLED) {
            restoreStock(order);
        } else if (oldStatus == OrderStatus.CANCELLED && newStatus != OrderStatus.CANCELLED) {
            deductStock(order);
        }
        order.setStatus(newStatus);
        persist();
    }

    public synchronized Optional<Order> findOrder(int id) {
        return database.orders.stream().filter(order -> order.getId() == id).findFirst();
    }

    public synchronized List<User> getUsers() {
        ArrayList<User> copy = new ArrayList<>(database.users);
        copy.sort(Comparator.comparing(User::isActive).reversed().thenComparing(User::getId));
        return copy;
    }

    public synchronized Optional<User> findUser(int id) {
        return database.users.stream().filter(user -> user.getId() == id).findFirst();
    }

    public synchronized Optional<User> findUserByUsername(String username) {
        if (username == null) {
            return Optional.empty();
        }
        String key = username.trim().toLowerCase(Locale.ROOT);
        return database.users.stream()
                .filter(user -> user.getUsername().toLowerCase(Locale.ROOT).equals(key))
                .findFirst();
    }

    public synchronized Optional<User> authenticate(String username, String password) {
        return findUserByUsername(username)
                .filter(User::isActive)
                .filter(user -> PasswordUtil.matches(password == null ? "" : password, user.getPasswordHash()));
    }

    public synchronized void saveUser(Integer id, String username, String displayName, String password, Role role,
            boolean active) {
        if (isBlank(username) || isBlank(displayName)) {
            throw new IllegalArgumentException("账号和姓名不能为空");
        }
        String cleanUsername = username.trim();
        Optional<User> duplicate = findUserByUsername(cleanUsername);
        if (id == null || id == 0) {
            if (duplicate.isPresent()) {
                throw new IllegalArgumentException("账号已存在");
            }
            if (isBlank(password)) {
                throw new IllegalArgumentException("新用户密码不能为空");
            }
            database.users.add(new User(database.nextUserId++, cleanUsername, displayName.trim(),
                    PasswordUtil.hash(password), role == null ? Role.VIEWER : role, active));
        } else {
            User user = findUser(id).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
            if (duplicate.isPresent() && duplicate.get().getId() != id) {
                throw new IllegalArgumentException("账号已存在");
            }
            user.setUsername(cleanUsername);
            user.setDisplayName(displayName.trim());
            if (!isBlank(password)) {
                user.setPasswordHash(PasswordUtil.hash(password));
            }
            user.setRole(role == null ? Role.VIEWER : role);
            user.setActive(active);
        }
        persist();
    }

    public synchronized void updateUserProfile(int userId, String username, String displayName, String password) {
        User user = findUser(userId).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (isBlank(username) || isBlank(displayName)) {
            throw new IllegalArgumentException("用户名和姓名不能为空");
        }
        Optional<User> duplicate = findUserByUsername(username);
        if (duplicate.isPresent() && duplicate.get().getId() != userId) {
            throw new IllegalArgumentException("用户名已存在");
        }
        user.setUsername(username.trim());
        user.setDisplayName(displayName.trim());
        if (!isBlank(password)) {
            user.setPasswordHash(PasswordUtil.hash(password));
        }
        persist();
    }

    public synchronized AiSettings getAiSettings() {
        if (database.aiSettings == null) {
            database.aiSettings = new AiSettings();
            persist();
        }
        return database.aiSettings;
    }

    public synchronized void saveAiSettings(String apiKey, String apiUrl, String model) {
        if (database.aiSettings == null) {
            database.aiSettings = new AiSettings();
        }
        if (!isBlank(apiKey)) {
            database.aiSettings.setApiKey(apiKey.trim());
        }
        database.aiSettings.setApiUrl(isBlank(apiUrl) ? "https://api.deepseek.com/chat/completions" : apiUrl.trim());
        database.aiSettings.setModel(isBlank(model) ? "deepseek-v4-flash" : model.trim());
        persist();
    }

    public synchronized int getProductCount() {
        return getActiveProducts().size();
    }

    public synchronized int getLowStockCount() {
        int count = 0;
        for (Product product : getActiveProducts()) {
            if (product.getStock() <= 8) {
                count++;
            }
        }
        return count;
    }

    public synchronized int getOrderCount() {
        return database.orders.size();
    }

    public synchronized double getSalesAmount() {
        double total = 0;
        for (Order order : database.orders) {
            if (order.getStatus() != OrderStatus.CANCELLED) {
                total += order.getTotal();
            }
        }
        return total;
    }

    public synchronized String buildBusinessSnapshot() {
        StringBuilder snapshot = new StringBuilder();
        snapshot.append("平台名称: 青原优品\n");
        snapshot.append("定位: 青海特色农牧、非遗与健康产品电商后台\n");
        snapshot.append("商品总数: ").append(getProductCount()).append('\n');
        snapshot.append("低库存商品数: ").append(getLowStockCount()).append('\n');
        snapshot.append("订单数量: ").append(getOrderCount()).append('\n');
        snapshot.append("有效销售额: ").append(String.format(Locale.ROOT, "%.2f", getSalesAmount())).append('\n');
        snapshot.append("\n商品清单:\n");
        for (Product product : getActiveProducts()) {
            snapshot.append("- ")
                    .append(product.getName())
                    .append("，分类: ").append(product.getCategory())
                    .append("，品牌: ").append(product.getBrand())
                    .append("，产地: ").append(product.getOrigin())
                    .append("，价格: ").append(String.format(Locale.ROOT, "%.2f", product.getPrice()))
                    .append("，库存: ").append(product.getStock())
                    .append("，属性: ").append(product.getAttributes())
                    .append("，简介: ").append(product.getDescription())
                    .append('\n');
        }
        snapshot.append("\n最近订单:\n");
        int count = 0;
        for (Order order : getOrders(null)) {
            if (count++ >= 20) {
                break;
            }
            snapshot.append("- #").append(order.getId())
                    .append("，客户: ").append(order.getCustomerName())
                    .append("，金额: ").append(String.format(Locale.ROOT, "%.2f", order.getTotal()))
                    .append("，状态: ").append(order.getStatus().getLabel())
                    .append('\n');
        }
        return snapshot.toString();
    }

    private void deductStock(Order order) {
        for (OrderItem item : order.getItems()) {
            Product product = findProduct(item.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("订单商品不存在"));
            if (product.getStock() < item.getQuantity()) {
                throw new IllegalArgumentException("恢复订单失败，商品「" + product.getName() + "」库存不足");
            }
            product.setStock(product.getStock() - item.getQuantity());
        }
    }

    private void restoreStock(Order order) {
        for (OrderItem item : order.getItems()) {
            Product product = findProduct(item.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("订单商品不存在"));
            product.setStock(product.getStock() + item.getQuantity());
        }
    }

    private void validateProduct(String name, String category, double price, int stock) {
        if (isBlank(name) || isBlank(category)) {
            throw new IllegalArgumentException("商品名称和分类不能为空");
        }
        if (price < 0) {
            throw new IllegalArgumentException("价格不能为负数");
        }
        if (stock < 0) {
            throw new IllegalArgumentException("库存不能为负数");
        }
    }

    private void load() {
        try {
            Files.createDirectories(dataFile.getParent());
            if (!Files.exists(dataFile)) {
                database = new Database();
                persist();
                return;
            }
            try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(dataFile))) {
                Object value = input.readObject();
                database = value instanceof Database ? (Database) value : new Database();
            }
        } catch (IOException | ClassNotFoundException | RuntimeException ex) {
            database = new Database();
        }
    }

    private void persist() {
        try {
            Files.createDirectories(dataFile.getParent());
            try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(dataFile))) {
                output.writeObject(database);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("无法保存数据文件: " + ex.getMessage(), ex);
        }
    }

    private void ensureDefaults() {
        boolean changed = false;
        if (database.products == null) {
            database.products = new ArrayList<>();
            changed = true;
        }
        if (database.orders == null) {
            database.orders = new ArrayList<>();
            changed = true;
        }
        if (database.users == null) {
            database.users = new ArrayList<>();
            changed = true;
        }
        if (database.nextProductId <= 0) {
            database.nextProductId = 1;
            changed = true;
        }
        if (database.nextOrderId <= 0) {
            database.nextOrderId = 10001;
            changed = true;
        }
        if (database.nextUserId <= 0) {
            database.nextUserId = 1;
            changed = true;
        }
        if (database.aiSettings == null) {
            database.aiSettings = new AiSettings();
            changed = true;
        }
        changed |= ensureDefaultUser("admin", "系统管理员", "admin123", Role.ADMIN);
        changed |= ensureDefaultUser("user", "普通用户", "user123", Role.VIEWER);
        changed |= ensureQinghaiProducts();
        changed |= ensureSampleOrders();
        if (changed) {
            persist();
        }
    }

    private boolean ensureDefaultUser(String username, String displayName, String password, Role role) {
        if (findUserByUsername(username).isPresent()) {
            return false;
        }
        database.users.add(new User(database.nextUserId++, username, displayName, PasswordUtil.hash(password), role,
                true));
        return true;
    }

    private boolean ensureQinghaiProducts() {
        boolean changed = false;
        changed |= upsertProduct("青海牦牛肉礼盒", "高原肉食", "青原牧选", "青海玉树/果洛",
                "高蛋白；低脂；礼盒装",
                "青海牦牛肉来自高海拔牧区，肉质紧实，纤维分明，带有高原畜牧产品特有的醇厚风味。礼盒内以即食或便携小包装为主，方便日常佐餐、户外补给和亲友分享。产品突出高蛋白、低脂肪和地方牧区特色，口感耐嚼，香味浓郁，是青海高原肉食类特产的代表之一。",
                128.00, 36, localImage("牦牛肉.jpg"), localImage("牦牛肉 (2).jpg"));
        changed |= upsertProduct("青海冬虫夏草精选", "滋补养生", "青原臻草", "青海玉树",
                "珍贵滋补；干货；礼盒装",
                "青海冬虫夏草生长于青藏高原高寒草甸环境，外形呈虫体与子座相连的自然形态，色泽温润，质地干燥。精选规格更注重完整度、洁净度和外观一致性，适合用于滋补礼盒、家庭养生收藏和传统食养场景。产品本身具有鲜明的高原地域属性，是青海特色滋补干货中辨识度较高的一类。",
                1680.00, 8, localImage("冬虫夏草.jpg"), localImage("冬虫夏草 (2).jpg"));
        changed |= upsertProduct("高原冷水三文鱼", "冷链生鲜", "青原冷水渔场", "青海龙羊峡",
                "冷链；鲜食；高蛋白",
                "高原冷水三文鱼产自青海冷水水域，鱼肉色泽鲜亮，脂肪纹理清晰，口感细嫩。产品以冷链形式保存与配送，适合刺身、煎烤、沙拉和家庭轻食等多种食用方式。青海冷水环境赋予其清爽的风味和稳定的肉质表现，是高原现代渔业与特色生鲜结合的代表产品。",
                198.00, 18, localImage("三文鱼.jpg"), localImage("三文鱼 (2).jpg"));
        changed |= upsertProduct("柴达木黑枸杞", "高原干货", "青原柴达木", "青海海西",
                "花青素；干果；泡饮",
                "柴达木黑枸杞颗粒小巧，果色深紫近黑，泡水后会呈现自然的紫红或蓝紫色泽。它产自青海海西地区，具有高原干燥气候孕育出的浓郁果香和干货质感。日常可用于温水冲泡、茶饮搭配、甜品点缀或礼盒组合，外观独特，颜色表现鲜明，是青海高原干果类产品中的特色单品。",
                89.00, 42, localImage("黑枸杞.jpg"), localImage("黑枸杞.webp"));
        changed |= upsertProduct("唐卡与刺绣文创", "非遗文创", "青原非遗工坊", "青海同仁/西宁",
                "手工；文创；摆件",
                "唐卡与刺绣文创融合青海民族艺术元素，图案色彩饱满，线条细密，常见题材包含吉祥纹样、高原风物和传统装饰图案。产品以摆件、挂饰、布艺或小型文创礼品形式呈现，兼具观赏性与纪念意义。它体现了青海非遗工艺的细腻审美，适合陈列、收藏和作为具有地域文化记忆的伴手礼。",
                268.00, 15, localImage("唐卡刺绣1.png"), localImage("唐卡刺绣2.png"));
        changed |= upsertProduct("青稞酒礼盒", "高原饮品", "青原青稞坊", "青海互助",
                "青稞酿造；礼盒；地方酒饮",
                "青稞酒以高原青稞为主要原料，酒体带有谷物发酵后的清香和地方酒饮的醇厚口感。礼盒包装更适合节庆、走亲访友和地方特产展示场景。青稞作为青海高原重要农作物，赋予产品鲜明的地域识别度，入口风味质朴，回味中带有粮食酒的温润香气。",
                158.00, 27, localImage("青稞酒1.png"), localImage("青稞酒2.png"));
        changed |= upsertProduct("中藏药养生包", "民族医药", "青原本草", "青海藏区",
                "草本；养生；非处方展示",
                "中藏药养生包选取具有高原民族医药文化特色的草本材料，以干燥小包或组合包形式呈现。产品强调草本清香、便携收纳和日常食养氛围，适合用于泡饮、香囊展示或健康主题礼盒搭配。本商品仅作为草本文化产品展示，不替代药品，也不作疾病治疗承诺。",
                76.00, 24, localImage("中藏药1.png"), localImage("中藏药2.png"));
        changed |= upsertProduct("青海藏毯", "家居织物", "青原织造", "青海西宁",
                "羊毛；手工感；家居",
                "青海藏毯以厚实织物质感和民族纹样为主要特点，常见图案具有高原文化审美和装饰意味。产品可用于客厅、书房、民宿空间或文化展陈，触感扎实，视觉层次丰富。藏毯工艺体现了青海织造传统与家居审美的结合，是具有实用性和装饰性的高原家居产品。",
                980.00, 11, localImage("藏毯1.jpg"), localImage("藏毯2.webp"));
        changed |= upsertProduct("高原藜麦", "粮油杂粮", "青原谷仓", "青海海西",
                "杂粮；轻食；高原种植",
                "高原藜麦颗粒细小，煮熟后口感柔韧，带有淡淡谷物清香。青海高海拔、昼夜温差明显的种植环境，使其具有鲜明的高原杂粮属性。产品适合煮粥、拌饭、轻食沙拉和家庭主食搭配，烹饪方式简单，营养结构均衡，是日常餐桌中容易搭配的青海特色粮食产品。",
                49.90, 56, localImage("藜麦1.webp"), localImage("藜麦2.webp"));
        return changed;
    }

    private boolean upsertProduct(String name, String category, String brand, String origin, String attributes,
            String description, double price, int stock, String image1, String image2) {
        for (Product product : database.products) {
            if (product.getName().equals(name)) {
                boolean changed = false;
                changed |= setIfDifferent(product::getCategory, product::setCategory, category);
                changed |= setIfDifferent(product::getBrand, product::setBrand, brand);
                changed |= setIfDifferent(product::getOrigin, product::setOrigin, origin);
                changed |= setIfDifferent(product::getAttributes, product::setAttributes, attributes);
                changed |= setIfDifferent(product::getDescription, product::setDescription, description);
                changed |= setIfDifferent(product::getImageUrl, product::setImageUrl, image1);
                changed |= setIfDifferent(product::getImageUrl2, product::setImageUrl2, image2);
                if (product.getPrice() != price) {
                    product.setPrice(price);
                    changed = true;
                }
                if (product.getStock() != stock) {
                    product.setStock(stock);
                    changed = true;
                }
                if (!product.isActive()) {
                    product.setActive(true);
                    changed = true;
                }
                return changed;
            }
        }
        database.products.add(new Product(database.nextProductId++, name, category, brand, origin, attributes,
                description, price, stock, true, image1, image2));
        return true;
    }

    private boolean setIfDifferent(Reader reader, Writer writer, String value) {
        if (!reader.get().equals(value)) {
            writer.set(value);
            return true;
        }
        return false;
    }

    private boolean ensureSampleOrders() {
        String[] names = {
                "马晓燕", "李文博", "赵雅婷", "王建国", "陈思远", "刘海宁", "周雪梅", "孙一鸣", "韩佳宁", "吴俊杰",
                "郭明辉", "杨若曦"
        };
        String[] phones = {
                "13997110001", "13897110002", "13797110003", "13697110004", "13597110005", "13497110006",
                "13997110007", "13897110008", "13797110009", "13697110010", "13597110011", "13497110012"
        };
        OrderStatus[] statuses = {
                OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.FINISHED, OrderStatus.PENDING,
                OrderStatus.FINISHED, OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.FINISHED,
                OrderStatus.PENDING, OrderStatus.PAID, OrderStatus.FINISHED, OrderStatus.SHIPPED
        };
        List<Product> products = getActiveProducts();
        if (products.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (int i = database.orders.size(); i < names.length; i++) {
            Product product = products.get(i % products.size());
            int quantity = i % 3 + 1;
            product.setStock(Math.max(0, product.getStock() - quantity));
            OrderItem item = new OrderItem(product.getId(), product.getName(), product.getPrice(), quantity);
            database.orders.add(new Order(database.nextOrderId++, names[i], phones[i],
                    "青海省西宁市城西区高原路 " + (18 + i) + " 号",
                    LocalDateTime.now().minusDays(i + 1), statuses[i], List.of(item), i % 2 == 0 ? "微信支付" : "支付宝支付"));
            changed = true;
        }
        return changed;
    }

    private String cleanPaymentMethod(String paymentMethod) {
        if (isBlank(paymentMethod)) {
            return "未选择";
        }
        String value = paymentMethod.trim();
        if ("WECHAT".equalsIgnoreCase(value) || "微信".equals(value) || "微信支付".equals(value)) {
            return "微信支付";
        }
        if ("ALIPAY".equalsIgnoreCase(value) || "支付宝".equals(value) || "支付宝支付".equals(value)) {
            return "支付宝支付";
        }
        return value;
    }

    private String localImage(String fileName) {
        return "/product-file?name=" + fileName;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private interface ValueReader {
        String read(Product product);
    }

    private interface Reader {
        String get();
    }

    private interface Writer {
        void set(String value);
    }

    private static class Database implements Serializable {
        private static final long serialVersionUID = 1L;

        private int nextProductId = 1;
        private int nextOrderId = 10001;
        private int nextUserId = 1;
        private List<Product> products = new ArrayList<>();
        private List<Order> orders = new ArrayList<>();
        private List<User> users = new ArrayList<>();
        private AiSettings aiSettings = new AiSettings();
    }
}
