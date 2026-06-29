package minishop.model;

public enum Role {
    ADMIN("管理员"),
    OPERATOR("普通用户"),
    VIEWER("普通用户");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean isAdmin() {
        return this == ADMIN;
    }

    public boolean canManageUsers() {
        return isAdmin();
    }

    public boolean canManageProducts() {
        return isAdmin();
    }

    public boolean canManageOrders() {
        return isAdmin();
    }

    public boolean canUseAi() {
        return isAdmin();
    }
}
