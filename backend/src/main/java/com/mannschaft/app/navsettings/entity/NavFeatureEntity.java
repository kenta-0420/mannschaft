package com.mannschaft.app.navsettings.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "nav_features")
@Getter
@Setter
@NoArgsConstructor
public class NavFeatureEntity {

    @Id
    @Column(name = "`key`", nullable = false, length = 50)
    private String key;

    @Column(name = "label_key", nullable = false, length = 100)
    private String labelKey;

    @Column(name = "icon", nullable = false, length = 50)
    private String icon;

    @Column(name = "path", nullable = false, length = 200)
    private String path;

    @Column(name = "is_fixed", nullable = false)
    private boolean fixed;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled;

    @Column(name = "subscription_required", nullable = false)
    private boolean subscriptionRequired;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "mobile_visible", nullable = false)
    private boolean mobileVisible;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
