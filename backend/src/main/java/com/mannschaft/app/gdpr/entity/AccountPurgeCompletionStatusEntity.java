package com.mannschaft.app.gdpr.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * GDPR Art.17 削除完了証跡エンティティ。
 *
 * <p>{@link com.mannschaft.app.gdpr.service.AccountPurgeService#purgeUser(com.mannschaft.app.auth.entity.UserEntity)}
 * が {@link com.mannschaft.app.gdpr.event.AccountPurgedEvent} 発火前に 6 ドメイン分の PENDING レコードを INSERT し、
 * 各ドメインの {@code *PurgeEventListener} が処理完了時に SUCCESS に更新する。</p>
 *
 * <p>PENDING のまま 2 時間以上経過したレコードは
 * {@link com.mannschaft.app.gdpr.batch.GdprPurgeAuditBatchService} が毎日 05:00 に検出し、
 * アラートログを出力する（GDPR Art.17「30日以内削除完了」の監査証跡として機能する）。</p>
 *
 * <h2>設計上の注意</h2>
 * <ul>
 *   <li>{@code user_id} は FK 制約なし（CLAUDE.md 原則 1: クロスドメイン FK 禁止）</li>
 *   <li>{@code email_hash} は SHA-256 ハッシュのみ（生 email は持たない）</li>
 *   <li>主キーは UUIDv7（CLAUDE.md 原則 6: 新規テーブルは UUIDv7）</li>
 * </ul>
 *
 * <p>設計根拠: {@code docs/architecture/account_purge_cross_domain_refactor.md} §4 Phase D-8</p>
 */
@Entity
@Table(name = "account_purge_completion_status")
@Getter
@Setter
@NoArgsConstructor
public class AccountPurgeCompletionStatusEntity extends UuidV7Entity {

    /**
     * 削除対象ユーザーの ID。
     * users テーブルへの FK 制約なし（クロスドメイン FK 禁止原則）。
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 削除時点の email を SHA-256 でハッシュ化した値。
     * GDPR 証跡として保持するが、生 email は保持しない。
     */
    @Column(name = "email_hash", nullable = false, length = 64)
    private String emailHash;

    /**
     * ドメイン識別子。
     * 取りうる値: {@code role} / {@code team} / {@code payment} / {@code chart} / {@code proxy} / {@code errorreport}
     */
    @Column(name = "domain_name", nullable = false, length = 50)
    private String domainName;

    /**
     * 処理状態。
     * <ul>
     *   <li>{@code PENDING}: AccountPurgeService が挿入した初期状態</li>
     *   <li>{@code SUCCESS}: 対応する *PurgeEventListener が完了時に更新</li>
     * </ul>
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /**
     * PENDING レコード作成日時（AccountPurgeService#purgeUser 実行時刻）。
     */
    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;

    /**
     * SUCCESS に更新された日時（*PurgeEventListener 完了時刻）。
     * PENDING 中は null。
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * 管理者による手動 retry の累計実行回数。
     * Phase F で追加。retry を一度も実行していない場合は 0。
     */
    @Column(name = "retry_count", nullable = false, columnDefinition = "TINYINT UNSIGNED NOT NULL DEFAULT 0")
    private Integer retryCount = 0;

    /**
     * 管理者が最後に retry を実行した日時。
     * Phase F で追加。一度も retry していない場合は null。
     */
    @Column(name = "last_retried_at")
    private LocalDateTime lastRetriedAt;
}
