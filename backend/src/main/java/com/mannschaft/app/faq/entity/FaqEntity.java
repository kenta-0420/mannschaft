package com.mannschaft.app.faq.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.faq.ScopeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * F21.1 §5.5: 公開FAQ（チーム/組織）。
 *
 * <p>固定6問（{@code questionKey} 非NULL・{@code questionText} NULL）と
 * 自由質問（{@code questionKey} NULL・{@code questionText} 非NULL）を1表で区別する。
 * 回答済み（{@code answerText} 非空）のもののみ FAQPage JSON-LD として出力する方針。</p>
 *
 * <p>設計原則（CLAUDE.md）:
 * <ul>
 *   <li>原則6: 主キーは UUIDv7（{@link UuidV7Entity} 継承）</li>
 *   <li>原則1: クロスドメインFK禁止 → {@code scopeId}（teams/organizations.id）・
 *       {@code createdBy}（users.id）は FK を張らず ID のみ保持。整合性はアプリ層で保証</li>
 * </ul>
 * </p>
 */
@Entity
@Table(name = "public_faqs")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class FaqEntity extends UuidV7Entity {

    /** スコープ種別（TEAM / ORGANIZATION）。String 保存（length 20）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private ScopeType scopeType;

    /** チーム/組織ID（FKなし・アプリ層整合）。 */
    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    /**
     * 固定質問キー（{@link com.mannschaft.app.faq.FixedFaqQuestion} の name）。
     * 自由質問の場合は NULL。
     */
    @Column(name = "question_key", length = 40)
    private String questionKey;

    /** 自由質問の質問文。固定質問の場合は NULL（質問文は FE の i18n で描画）。 */
    @Column(name = "question_text", length = 255)
    private String questionText;

    /** 回答本文。 */
    @Column(name = "answer_text", nullable = false, columnDefinition = "TEXT")
    private String answerText;

    /** 表示順（小さいほど先）。 */
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    /** 作成者 user_id（FKなし・indexのみ）。匿名化/不明の場合は NULL。 */
    @Column(name = "created_by")
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 論理削除日時。NULL なら有効。 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
