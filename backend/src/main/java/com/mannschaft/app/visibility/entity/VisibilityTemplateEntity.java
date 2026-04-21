package com.mannschaft.app.visibility.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 公開範囲テンプレートエンティティ。
 * ユーザーが保存したカスタム公開範囲テンプレートと、システムプリセットを管理する。
 * ownerUserId が NULL の場合はシステムプリセットを示す。
 */
@Entity
@Table(name = "visibility_templates")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VisibilityTemplateEntity {

    /** プライマリキー */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * テンプレート所有ユーザーID。
     * NULL の場合はシステムプリセットを示す。
     */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    /** テンプレート名（最大60文字） */
    @Column(nullable = false, length = 60)
    private String name;

    /** テンプレートの説明（最大240文字、NULL許容） */
    @Column(length = 240)
    private String description;

    /** アイコン絵文字（NULL許容） */
    @Column(name = "icon_emoji", length = 16)
    private String iconEmoji;

    /**
     * システムプリセットフラグ。
     * true の場合は全ユーザーが利用可能なシステム定義テンプレート。
     */
    @Column(name = "is_system_preset", nullable = false)
    @Builder.Default
    private boolean isSystemPreset = false;

    /**
     * システムプリセットの識別キー（NULL許容）。
     * プリセットのみ設定される一意キー（例: "PUBLIC", "FRIENDS_ONLY"）。
     */
    @Column(name = "preset_key", length = 64)
    private String presetKey;

    /** レコード作成日時（自動設定） */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** レコード更新日時（自動設定） */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * このテンプレートに紐づくルール一覧。
     * テンプレートが削除された場合、ルールも CASCADE 削除される。
     */
    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<VisibilityTemplateRuleEntity> rules = new ArrayList<>();
}
