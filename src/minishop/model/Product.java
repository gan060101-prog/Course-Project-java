package minishop.model;

import java.io.Serializable;

public class Product implements Serializable {
    private static final long serialVersionUID = 2L;

    private final int id;
    private String name;
    private String category;
    private String brand;
    private String origin;
    private String attributes;
    private String description;
    private double price;
    private int stock;
    private boolean active;
    private String imageUrl;
    private String imageUrl2;

    public Product(int id, String name, String category, double price, int stock, boolean active) {
        this(id, name, category, "", "", "", "", price, stock, active, "", "");
    }

    public Product(int id, String name, String category, double price, int stock, boolean active, String imageUrl) {
        this(id, name, category, "", "", "", "", price, stock, active, imageUrl, "");
    }

    public Product(int id, String name, String category, String brand, String origin, String attributes,
            String description, double price, int stock, boolean active, String imageUrl, String imageUrl2) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.brand = brand;
        this.origin = origin;
        this.attributes = attributes;
        this.description = description;
        this.price = price;
        this.stock = stock;
        this.active = active;
        this.imageUrl = imageUrl;
        this.imageUrl2 = imageUrl2;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return safe(name);
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return safe(category);
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getBrand() {
        return safe(brand);
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public String getOrigin() {
        return safe(origin);
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getAttributes() {
        return safe(attributes);
    }

    public void setAttributes(String attributes) {
        this.attributes = attributes;
    }

    public String getDescription() {
        return safe(description);
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public int getStock() {
        return stock;
    }

    public void setStock(int stock) {
        this.stock = stock;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getImageUrl() {
        return safe(imageUrl);
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getImageUrl2() {
        return safe(imageUrl2);
    }

    public void setImageUrl2(String imageUrl2) {
        this.imageUrl2 = imageUrl2;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
