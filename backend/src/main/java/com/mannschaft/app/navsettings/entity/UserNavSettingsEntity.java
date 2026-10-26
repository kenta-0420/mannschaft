package com.mannschaft.app.navsettings.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "user_nav_settings")
@Getter
@Setter
@NoArgsConstructor
public class UserNavSettingsEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    // JSON配列を文字列として保持: ["todo","my-shift"]
    // サービス層でObjectMapperを使ってList<String>に変換する
    @Column(name = "hidden_nav_keys", nullable = false, columnDefinition = "JSON")
    private String hiddenNavKeys;

    // 個人別ナビ表示順。nav_features.key の配列（JSON）。
    // NULL = マスタ sort_order 順で表示する。
    @Column(name = "nav_display_order", columnDefinition = "JSON")
    private String navDisplayOrder;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void onSave() {
        updatedAt = Instant.now();
    }
}
