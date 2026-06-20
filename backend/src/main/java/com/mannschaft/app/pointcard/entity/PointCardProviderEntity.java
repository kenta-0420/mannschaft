package com.mannschaft.app.pointcard.entity;

import com.mannschaft.app.common.entity.UuidV7CharEntity;
import com.mannschaft.app.pointcard.enums.BarcodeFormat;
import com.mannschaft.app.pointcard.enums.PointCardCategory;
import com.mannschaft.app.pointcard.enums.PointCardProviderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * ポイントカードプロバイダー（運営マスタ + Phase 2 自店発行）エンティティ。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §5.1
 *
 * <p>Phase 1 では「ロゴ・ブランドカラー補強用マスタ」として人気 10〜15 社の
 * プリセットを提供する。{@code type=EXTERNAL} のみが Seed 投入される。
 * Phase 2 では organization が自店スタンプ・残高カードを発行する際の
 * プロバイダーレコードとして利用される（{@code organization_id} 補完）。
 *
 * <p>CLAUDE.md 原則 6 に従い PK は UUIDv7（CHAR(36)）。
 * {@code organization_id} に対する FK は張らず INDEX のみで運用する
 * （CLAUDE.md 原則 1 クロスドメイン弱参照）。
 */
@Entity
@Table(name = "point_card_providers")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class PointCardProviderEntity extends UuidV7CharEntity {

    /** 一意コード（例: tokyu_point, dpoint）。fuzzy match の正規化マッチ対象。 */
    @Column(name = "code", nullable = false, length = 50, unique = true)
    private String code;

    /** 表示名（例: 「東急ポイント」）。fuzzy match の正規化マッチ対象。 */
    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    /** カテゴリ（RETAIL / CONVENIENCE / FOOD / TRANSPORT / OTHER）。 */
    @Column(name = "category", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private PointCardCategory category;

    /** カード種別（EXTERNAL / SELF_ISSUED_STAMP / SELF_ISSUED_BALANCE）。 */
    @Column(name = "type", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private PointCardProviderType type = PointCardProviderType.EXTERNAL;

    /** Phase 1 では常に NULL。Phase 2 で自店発行プロバイダーの所属組織を設定。 */
    @Column(name = "organization_id")
    private Long organizationId;

    /** ロゴ R2 オブジェクトキー。 */
    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    /** ブランドカラー（例: #E60012）。 */
    @Column(name = "brand_color", length = 7)
    private String brandColor;

    /** 既定のバーコード形式（プリセットタップ時のフォーム初期値）。 */
    @Column(name = "default_barcode_format", length = 20)
    @Enumerated(EnumType.STRING)
    private BarcodeFormat defaultBarcodeFormat;

    /** カード番号の正規表現（参考バリデーション用）。 */
    @Column(name = "card_number_regex", length = 200)
    private String cardNumberRegex;

    /** UI に表示するカード番号桁数ヒント（例: 「13 桁の数字」）。 */
    @Column(name = "card_number_length_hint", length = 50)
    private String cardNumberLengthHint;

    /** プロバイダーが利用可能か（運営側で停止可能）。 */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean active = Boolean.TRUE;

    /** 各プロバイダー固有の注意書き（多言語キー or 日本語デフォルト）。 */
    @Column(name = "legal_notice", columnDefinition = "TEXT")
    private String legalNotice;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.active == null) {
            this.active = Boolean.TRUE;
        }
        if (this.type == null) {
            this.type = PointCardProviderType.EXTERNAL;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
