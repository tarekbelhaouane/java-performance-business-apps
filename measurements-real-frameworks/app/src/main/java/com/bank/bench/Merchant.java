package com.bank.bench;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "merchant")
public class Merchant {
    @Id
    private Long id;
    private String name;
    private String category;

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getCategory() { return category; }
}
