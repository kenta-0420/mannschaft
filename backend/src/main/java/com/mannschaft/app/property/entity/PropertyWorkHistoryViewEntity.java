package com.mannschaft.app.property.entity;

import com.mannschaft.app.property.HistoryViewAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * 物件履歴閲覧監査ログエンティティ。
 * ADMIN が誰が金額情報を見たかを追跡できる。
 * F09.13 設計書 §3 property_work_history_views テーブル定義に対応。
 *
 * 論理削除なし。日次バッチで 90日経過後に物理削除（F10.3 audit_logs と整合）。
 * BaseEntity を継承しない（updated_at 不要、created_at は viewed_at で代替）。
 */
@Entity
@Table(name = "property_work_history_views")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
public class PropertyWorkHistoryViewEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long packageId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HistoryViewAction action;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime viewedAt = LocalDateTime.now();

    /** IPv6 対応（最大45文字）。 */
    @Column(length = 45)
    private String ipAddress;

    @Column(length = 255)
    private String userAgent;
}
