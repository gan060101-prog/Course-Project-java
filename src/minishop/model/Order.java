package minishop.model;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Order implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int id;
    private final String customerName;
    private final String phone;
    private final String address;
    private final LocalDateTime createdAt;
    private OrderStatus status;
    private final List<OrderItem> items;
    private String paymentMethod;

    public Order(int id, String customerName, String phone, String address, LocalDateTime createdAt,
            OrderStatus status, List<OrderItem> items) {
        this(id, customerName, phone, address, createdAt, status, items, "");
    }

    public Order(int id, String customerName, String phone, String address, LocalDateTime createdAt,
            OrderStatus status, List<OrderItem> items, String paymentMethod) {
        this.id = id;
        this.customerName = customerName;
        this.phone = phone;
        this.address = address;
        this.createdAt = createdAt;
        this.status = status;
        this.items = new ArrayList<>(items);
        this.paymentMethod = paymentMethod == null ? "" : paymentMethod;
    }

    public int getId() {
        return id;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getPhone() {
        return phone;
    }

    public String getAddress() {
        return address;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public String getPaymentMethod() {
        return paymentMethod == null || paymentMethod.isBlank() ? "未记录" : paymentMethod;
    }

    public double getTotal() {
        double total = 0;
        for (OrderItem item : items) {
            total += item.getSubtotal();
        }
        return total;
    }
}
