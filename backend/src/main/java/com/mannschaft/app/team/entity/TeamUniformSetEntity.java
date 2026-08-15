package com.mannschaft.app.team.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.team.UniformSetKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * ユニフォームセットエンティティ（F08.7.1/05 §8.2）。
 *
 * <p>チーム単位の色テンプレ。FP / GK 正 / GK 副の 3 種を各シャツ/パンツ/ソックス色で保持し、
 * メンバー表提出時に着用セットとして参照する（roster.uniform_set_id）。</p>
 *
 * <p>原則準拠:</p>
 * <ul>
 *   <li>新規テーブルゆえ主キーは UUIDv7（原則6・{@link UuidV7Entity} 継承）。</li>
 *   <li>{@code teamId} は team ドメインの ID 値（同一ドメイン）。クロスドメイン FK は張らない（原則1）。</li>
 *   <li>論理削除（soft delete）でテンプレ削除しても過去試合の参照を壊さない（原則3）。</li>
 * </ul>
 */
@Entity
@Table(name = "team_uniform_set")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class TeamUniformSetEntity extends UuidV7Entity {

    /** チームID（team ドメインの ID 参照） */
    @Column(nullable = false)
    private Long teamId;

    /** 種別（FP / GK_PRIMARY / GK_SECONDARY） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UniformSetKind kind;

    /** 表示名（例「ホーム白」・NULL 可） */
    @Column(length = 64)
    private String label;

    /** シャツ色（色名 or HEX を文字列で保持） */
    @Column(nullable = false, length = 32)
    private String shirtColor;

    /** パンツ色 */
    @Column(nullable = false, length = 32)
    private String shortsColor;

    /** ソックス色 */
    @Column(nullable = false, length = 32)
    private String socksColor;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * セット情報を更新する。
     */
    public void update(UniformSetKind kind, String label,
                       String shirtColor, String shortsColor, String socksColor) {
        if (kind != null) this.kind = kind;
        this.label = label;
        if (shirtColor != null) this.shirtColor = shirtColor;
        if (shortsColor != null) this.shortsColor = shortsColor;
        if (socksColor != null) this.socksColor = socksColor;
    }

    /**
     * 論理削除する。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
