package com.mannschaft.app.circulation.entity;

import com.mannschaft.app.circulation.RecipientStatus;
import com.mannschaft.app.common.BaseEntity;
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

import java.time.LocalDateTime;

/**
 * 回覧受信者エンティティ。回覧文書の受信者と押印状態を管理する。
 */
@Entity
@Table(name = "circulation_recipients")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
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
     * スキップする。
     */
    public void skip() {
        this.status = RecipientStatus.SKIPPED;
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
}
