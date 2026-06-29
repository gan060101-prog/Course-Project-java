package minishop.model;

public enum OrderStatus {
    PENDING("待处理"),
    PAID("已付款"),
    SHIPPED("已发货"),
    FINISHED("已完成"),
    CANCELLED("已取消");

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
