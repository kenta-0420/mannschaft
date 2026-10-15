package com.mannschaft.app.circulation.entity;

import com.mannschaft.app.circulation.RecipientStatus;
import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * 回覧受信者エンティティ。回覧文書の受信者と押印状態を管理する。
 */
@Entity
@Table(name = "circulation_recipients")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class CirculationRecipientEntity extends BaseEntity {

    @Column(nullable = false)
    private Long documentId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private RecipientStatus status = RecipientStatus.PENDING;

    private LocalDateTime stampedAt;

    @Column(name = "is_proxy_confirmed", nullable = false, columnDefinition = "TINYINT(1) DEFAULT 0")
    @Builder.Default
    private Boolean isProxyConfirmed = false;

    @Column(name = "proxy_input_record_id")
    private Long proxyInputRecordId;

    private Long sealId;

    @Column(length = 20)
    private String sealVariant;

    @Column(nullable = false)
    @Builder.Default
    private Short tiltAngle = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isFlipped = false;

    /** F05.2 Phase 11 第三陣 3-B: ADMIN 強制スキップ時の理由（self-skip は NULL）。 */
    @Column(length = 255)
    private String skipReason;

    /** F05.2 Phase 11 第三陣 3-B: スキップ操作実行者の user_id（self-skip は NULL）。 */
    private Long skippedBy;

    /** F05.2 Phase 11 第三陣 3-B: スキップ実行日時。 */
    private LocalDateTime skippedAt;

    /**
     * 押印する。
     *
     * @param sealId      印鑑ID
     * @param sealVariant 印鑑バリアント
     * @param tiltAngle   傾き角度
     * @param isFlipped   反転フラグ
     */
    public void stamp(Long sealId, String sealVariant, Short tiltAngle, Boolean isFlipped) {
        this.status = RecipientStatus.STAMPED;
        this.stampedAt = LocalDateTime.now();
        this.sealId = sealId;
        this.sealVariant = sealVariant;
        this.tiltAngle = tiltAngle != null ? tiltAngle : 0;
        this.isFlipped = isFlipped != null ? isFlipped : false;
    }

    /**
     * 押印を訂正する（受信者本人による）。
     *
     * <p>F05.2 Phase 11 第三陣 3-B: 押印済みの受信者が自分の押印を訂正する。
     * status は PENDING に戻り、再押印を促す。訂正前のスナップショットは
     * {@code CirculationStampCorrectionLogEntity} に記録される。</p>
     */
    public void correctStamp() {
        this.status = RecipientStatus.PENDING;
        this.stampedAt = null;
        this.sealId = null;
        this.sealVariant = null;
        this.tiltAngle = 0;
        this.isFlipped = false;
    }

    /**
     * スキップする（受信者本人によるセルフスキップ）。
     */
    public void skip() {
        this.status = RecipientStatus.SKIPPED;
        this.skippedAt = LocalDateTime.now();
    }

    /**
     * ADMIN による強制スキップを実行する。
     *
     * @param adminUserId スキップ操作を行った ADMIN の user_id
     * @param reason      スキップ理由
     */
    public void adminSkip(Long adminUserId, String reason) {
        this.status = RecipientStatus.SKIPPED;
        this.skipReason = reason;
        this.skippedBy = adminUserId;
        this.skippedAt = LocalDateTime.now();
    }

    /**
     * 拒否する。
     */
    public void reject() {
        this.status = RecipientStatus.REJECTED;
    }

    /**
     * 押印可能かどうかを判定する。
     *
     * @return PENDING ステータスの場合 true
     */
    public boolean isStampable() {
        return this.status == RecipientStatus.PENDING;
    }

    /**
     * 代理確認フラグと代理入力レコードIDを設定する。
     * managed エンティティを直接ミューテートして id を保持したまま UPDATE を発行する。
     * （toBuilder().build() は継承フィールド id を引き継がず INSERT 化するため使用しない）
     */
    public void applyProxyConfirmed(Long proxyInputRecordId) {
        this.isProxyConfirmed = true;
        this.proxyInputRecordId = proxyInputRecordId;
    }
}
