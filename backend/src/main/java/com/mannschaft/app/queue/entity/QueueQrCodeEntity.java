package com.mannschaft.app.queue.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import com.mannschaft.app.common.token.PlaintextSecretRequired;

/**
 * 順番待ちQRコードエンティティ。カテゴリまたはカウンターへの発券用QRコードを管理する。
 */
@Entity
@Table(name = "queue_qr_codes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class QueueQrCodeEntity extends BaseEntity {

    private Long categoryId;

    private Long counterId;

    @PlaintextSecretRequired(reason = "待ち行列の QR を運営者に何度でも見せるため平文で保持する。署名付き短命 QR への移行は別 CMP")
    @Column(nullable = false, unique = true, length = 64)
    private String qrToken;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /**
     * QRコードを無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * QRコードを有効化する。
     */
    public void activate() {
        this.isActive = true;
    }
}
