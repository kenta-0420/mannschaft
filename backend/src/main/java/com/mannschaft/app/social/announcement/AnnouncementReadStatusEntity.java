package com.mannschaft.app.social.announcement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * お知らせ既読管理エンティティ（F02.6）。
 *
 * <p>
 * ユーザーごとのお知らせ既読トラッキングテーブル {@code announcement_read_status} のエンティティ。
 * ウィジェットの「未読バッジ」「既読後のリストでの薄表示」などの UX 機能で使用する。
 * </p>
 *
 * <p>
 * <b>制約</b>:
 * <ul>
 *   <li>{@code (announcement_feed_id, user_id)} にユニーク制約があり重複既読は防止される</li>
 *   <li>親の {@code announcement_feeds} レコードが削除された場合 CASCADE 削除される</li>
 *   <li>ユーザーが退会した場合も CASCADE 削除される</li>
 *   <li>保持期間は 90 日（バッチで古いお知らせの既読レコードを物理削除）</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>注</b>: createdAt / updatedAt が不要なため {@link com.mannschaft.app.common.BaseEntity} は継承しない。
 * </p>
 */
@Entity
@Table(
        name = "announcement_read_status",
        // 本番 DDL（Flyway V13.020）の UNIQUE KEY uq_ars_feed_user を Entity 側にも宣言する。
        //
        // なぜ必要か（#2530 ⑤）: 本番は Flyway が制約を作るので ddl-auto: none でも問題ないが、
        // test プロファイルは ddl-auto: create で **Entity 定義からスキーマを生成**する。
        // ここに宣言が無いとテスト DB にだけ一意制約が存在せず、
        // 既読 INSERT の ON DUPLICATE KEY UPDATE（重複キーの無害化）が発火しないため、
        // 「本番では冪等だがテストでは重複行が増える」という乖離が生まれる。
        // 実際にこの宣言漏れは #2530 の冪等性 IT が重複行 2 件を検出して発覚した。
        uniqueConstraints = @UniqueConstraint(
                name = "uq_ars_feed_user",
                columnNames = {"announcement_feed_id", "user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class AnnouncementReadStatusEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 既読したお知らせフィードの ID。
     */
    @Column(nullable = false)
    private Long announcementFeedId;

    /**
     * 既読したユーザーの ID。
     */
    @Column(nullable = false)
    private Long userId;

    /**
     * 既読日時。INSERT 時に自動設定される。
     */
    @Column(nullable = false)
    private LocalDateTime readAt;

    /**
     * 代理確認フラグ（0=本人既読, 1=代理確認）。
     */
    @Column(name = "is_proxy_confirmed", nullable = false, columnDefinition = "TINYINT(1) DEFAULT 0")
    @Builder.Default
    private Boolean isProxyConfirmed = false;

    /**
     * 代理入力記録ID（proxy_input_records.id）。代理確認時のみセットされる。
     */
    @Column(name = "proxy_input_record_id")
    private Long proxyInputRecordId;

    /**
     * readAt が未設定の場合に現在時刻で補完する。
     */
    @PrePersist
    protected void onCreate() {
        if (this.readAt == null) {
            this.readAt = LocalDateTime.now();
        }
    }
}
