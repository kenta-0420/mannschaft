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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * QR チェックイン／アウト実績エンティティ。F13.1 Phase 13.1.2。
 *
 * <p>同一契約あたり {@link JobCheckInType#IN} / {@link JobCheckInType#OUT} は各 1 件のみ
 * （DB 側の {@code uq_jci_contract_type} で保証）。</p>
 *
 * <p>Geolocation は契約完了 90 日後のバッチで {@link #clearGeolocation(Instant)} により NULL 更新する。
 * 業務場所から 500 m 以上乖離した場合は {@link #markGeoAnomaly()} で {@code geoAnomaly=true} を立てる。</p>
 *
 * <p>設計書 §2.3.1 / §5.2 / §10.10 を参照。</p>
 */
@Entity
@Table(name = "job_check_ins")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class JobCheckInEntity extends BaseEntity {

    @Column(name = "job_contract_id", nullable = false)
    private Long jobContractId;

    @Column(name = "worker_user_id", nullable = false)
    private Long workerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private JobCheckInType type;

    /** 使用した QR トークン ID。物理削除時は ON DELETE SET NULL で NULL 化される（監査ログ残置）。 */
    @Column(name = "qr_token_id")
    private Long qrTokenId;

    @Column(name = "scanned_at", nullable = false, columnDefinition = "TIMESTAMP(3)")
    private Instant scannedAt;

    @Column(name = "server_received_at", nullable = false, columnDefinition = "TIMESTAMP(3)")
    private Instant serverReceivedAt;

    @Column(name = "offline_submitted", nullable = false)
    @Builder.Default
    private Boolean offlineSubmitted = false;

    @Column(name = "manual_code_fallback", nullable = false)
    @Builder.Default
    private Boolean manualCodeFallback = false;

    /** 端末緯度（DECIMAL(9,6)）。アプリ層で AES-256-GCM 暗号化して格納する想定（設計書 §10.10）。 */
    @Column(name = "geolocation_lat", precision = 9, scale = 6)
    private BigDecimal geolocationLat;

    /** 端末経度（DECIMAL(9,6)）。同上。 */
    @Column(name = "geolocation_lng", precision = 9, scale = 6)
    private BigDecimal geolocationLng;

    @Column(name = "geolocation_accuracy_m", precision = 8, scale = 2)
    private BigDecimal geolocationAccuracyM;

    /** 業務場所から 500 m 以上乖離した場合に true。 */
    @Column(name = "geo_anomaly", nullable = false)
    @Builder.Default
    private Boolean geoAnomaly = false;

    /** 位置情報削除日時（完了 90 日後バッチによる NULL 更新時に記録）。 */
    @Column(name = "geolocation_deleted_at", columnDefinition = "TIMESTAMP(3)")
    private Instant geolocationDeletedAt;

    @Column(name = "client_user_agent", length = 512)
    private String clientUserAgent;

    /**
     * 位置情報を削除し、削除日時を記録する（契約完了後 90 日の自動削除バッチから呼び出される）。
     *
     * @param at 削除日時
     */
    public void clearGeolocation(Instant at) {
        this.geolocationLat = null;
        this.geolocationLng = null;
        this.geolocationAccuracyM = null;
        this.geolocationDeletedAt = at;
    }

    /**
     * 業務場所からの乖離（500 m 以上）を検出した際にフラグを立てる。
     *
     * <p>自動拒否はせず、Requester へアラート通知する（GPS 精度問題の誤検出回避のため）。</p>
     */
    public void markGeoAnomaly() {
        this.geoAnomaly = true;
    }
}
