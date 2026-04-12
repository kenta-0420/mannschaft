package com.mannschaft.app.advertising.ranking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 同類チーム備品ランキングエンティティ。
 * チームテンプレート・カテゴリ単位の備品利用集計結果を保持する。
 */
@Entity
@Table(name = "equipment_rankings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class EquipmentRankingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** チームテンプレート（例: "soccer_youth"） */
    @Column(name = "team_template", nullable = false, length = 50)
    private String teamTemplate;

    /** カテゴリ（__ALL__ は全カテゴリ集計） */
    @Column(name = "category", nullable = false, length = 100)
    @Builder.Default
    private String category = "__ALL__";

    /** カテゴリ内順位 */
    @Column(name = "`rank`", nullable = false)
    private Short rank;

    /** 備品名（元の表記） */
    @Column(name = "item_name", nullable = false, length = 200)
    private String itemName;

    /** 正規化済み備品名（集計キー） */
    @Column(name = "normalized_name", nullable = false, length = 200)
    private String normalizedName;

    /** Amazon ASIN（補充リンク生成用） */
    @Column(name = "amazon_asin", length = 10)
    private String amazonAsin;

    /** ASIN信頼度スコア（0〜100） */
    @Column(name = "asin_confidence")
    private Short asinConfidence;

    /** この備品を保有しているチーム数 */
    @Column(name = "team_count", nullable = false)
    private Integer teamCount;

    /** チーム全体の合計保有数量 */
    @Column(name = "total_quantity", nullable = false)
    private Integer totalQuantity;

    /** 消費イベント発生回数 */
    @Column(name = "consume_event_count", nullable = false)
    @Builder.Default
    private Integer consumeEventCount = 0;

    /** ランキングスコア */
    @Column(name = "score", nullable = false, precision = 10, scale = 2)
    private BigDecimal score;

    /** 集計実施日時 */
    @Column(name = "calculated_at", nullable = false)
    private LocalDateTime calculatedAt;
}
