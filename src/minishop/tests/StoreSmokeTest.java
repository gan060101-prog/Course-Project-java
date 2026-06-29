package minishop.tests;

import minishop.model.Order;
import minishop.model.OrderStatus;
import minishop.model.Product;
import minishop.model.Role;
import minishop.store.DataStore;

import java.nio.file.Files;
import java.nio.file.Path;

public class StoreSmokeTest {
    public static void main(String[] args) throws Exception {
        Path tempData = Files.createTempDirectory("minishop-test-");
        DataStore store = new DataStore(tempData);

        assertTrue(store.authenticate("admin", "admin123").isPresent(), "默认管理员账号应可登录");
        assertEquals(Role.VIEWER, store.authenticate("user", "user123").orElseThrow().getRole(),
                "默认普通用户账号应为普通用户角色");

        assertTrue(store.searchProducts("牦牛肉").size() == 1, "应包含牦牛肉商品");
        assertTrue(store.searchProducts("冬虫夏草").size() == 1, "应包含冬虫夏草商品");
        assertTrue(store.searchProducts("藜麦").size() == 1, "应包含藜麦商品");

        Product yak = store.searchProducts("牦牛肉").get(0);
        assertTrue(!yak.getImageUrl().isBlank() && !yak.getImageUrl2().isBlank(), "每个核心商品应有两张图片");
        int beforeOrderStock = yak.getStock();

        Order order = store.createOrder("测试客户", "13600000000", "测试地址", yak.getId(), 1);
        assertEquals(OrderStatus.PENDING, order.getStatus(), "新订单状态应为待处理");
        assertEquals(beforeOrderStock - 1, store.findProduct(yak.getId()).orElseThrow().getStock(), "下单后应扣减库存");

        store.updateOrderStatus(order.getId(), OrderStatus.CANCELLED);
        assertEquals(beforeOrderStock, store.findProduct(yak.getId()).orElseThrow().getStock(), "取消订单应恢复库存");

        System.out.println("StoreSmokeTest passed.");
        System.out.println("Temporary test data: " + tempData);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + "，期望: " + expected + "，实际: " + actual);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
