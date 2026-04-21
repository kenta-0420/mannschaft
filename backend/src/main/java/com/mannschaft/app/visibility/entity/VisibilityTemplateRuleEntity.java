package com.mannschaft.app.visibility.entity;

import com.mannschaft.app.visibility.VisibilityTemplateRuleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 公開範囲テンプレートのルールエンティティ。
 * 1つのテンプレートに対して複数のルールが設定可能で、各ルールは OR 結合で評価される。
 */
@Entity
@Table(name = "visibility_template_rules")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VisibilityTemplateRuleEntity {

    /** プライマリキー */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 親テンプレートへの参照。
     * LAZY ロードで、テンプレート削除時は CASCADE によりルールも削除される。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id", nullable = false)
    private VisibilityTemplateEntity template;

    /**
     * ルール種別（VARCHAR として保存）。
     * どの条件でマッチングするかを示す。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 50)
    private VisibilityTemplateRuleType ruleType;

    /**
     * ルール対象の数値ID（NULL許容）。
     * チームID・組織ID・ユーザーIDなどを格納する。
     */
    @Column(name = "rule_target_id")
    private Long ruleTargetId;

    /**
     * ルール対象のテキスト値（NULL許容、最大120文字）。
     * 地域コードや social_profile のスラッグなど文字列の対象値を格納する。
     */
    @Column(name = "rule_target_text", length = 120)
    private String ruleTargetText;

    /**
     * ルールの表示順序。値が小さいほど先に表示される（デフォルト: 0）。
     */
    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    /** レコード作成日時（自動設定） */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
