package com.mannschaft.app.jobmatching.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.jobmatching.enums.JobCheckInType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * QR チェックイン／アウト用短命トークンエンティティ。F13.1 Phase 13.1.2。
 *
 * <p>発行時に HMAC-SHA256 署名付き JWT 互換ペイロードが生成され、本テーブルには
 * 検証・リプレイ防止に必要なメタ情報（{@code nonce} / {@code kid} / {@code shortCode} /
 * {@code issuedAt} / {@code expiresAt} / {@code usedAt}）を保持する。</p>
 *
 * <p>TTL デフォルト 60 秒。一度 {@link #markUsed(Instant)} されたトークンは
 * 再スキャン不可（アプリ層で {@link #isUsed()} を確認してから消費する）。</p>
 *
 * <p>設計書 §2.3.1 / §5.2 / §10.10 を参照。</p>
 */
@Entity
@Table(name = "job_qr_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class JobQrTokenEntity extends BaseEntity {

    @Column(name = "job_contract_id", nullable = false)
    private Long jobContractId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private JobCheckInType type;

    @Column(nullable = false, length = 36)
    private String nonce;

    @Column(nullable = false, length = 32)
    private String kid;

    @Column(name = "short_code", nullable = false, length = 6)
    private String shortCode;

    @Column(name = "issued_at", nullable = false, columnDefinition = "TIMESTAMP(3)")
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, columnDefinition = "TIMESTAMP(3)")
    private Instant expiresAt;

    @Column(name = "used_at", columnDefinition = "TIMESTAMP(3)")
    private Instant usedAt;

    @Column(name = "issued_by_user_id", nullable = false)
    private Long issuedByUserId;

    /**
     * トークンを消費済みとしてマークする。一度マークしたトークンは再スキャン不可。
     *
     * @param now 消費時刻
     */
    public void markUsed(Instant now) {
        this.usedAt = now;
    }

    /**
     * 指定時刻時点でトークンが失効しているか判定する。
     *
     * @param now 判定基準時刻
     * @return {@code now} が {@code expiresAt} 以降であれば {@code true}
     */
    public boolean isExpired(Instant now) {
        return !now.isBefore(this.expiresAt);
    }

    /**
     * 指定時刻が発行時刻〜失効時刻の有効範囲内にあるか判定する（両端含む判定ではなく、
     * {@code issuedAt <= t < expiresAt} の半開区間で評価）。
     *
     * <p>オフライン送信時の「スキャン時刻が有効範囲内であれば受け付ける」判定（設計書 §2.3.1）で使用する。</p>
     *
     * @param t 判定対象時刻（通常はクライアントでのスキャン時刻）
     * @return 有効範囲内であれば {@code true}
     */
    public boolean isWithinIssuedAndExpires(Instant t) {
        return !t.isBefore(this.issuedAt) && t.isBefore(this.expiresAt);
    }

    /**
     * 既に消費済みか判定する。
     */
    public boolean isUsed() {
        return this.usedAt != null;
    }
}
