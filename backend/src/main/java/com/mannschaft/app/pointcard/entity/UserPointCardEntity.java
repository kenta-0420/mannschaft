package com.mannschaft.app.pointcard.entity;

import com.mannschaft.app.common.EncryptedStringConverter;
import com.mannschaft.app.common.entity.UuidV7CharEntity;
import com.mannschaft.app.gdpr.PersonalData;
import com.mannschaft.app.pointcard.enums.BarcodeFormat;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * ユーザーが保有するポイントカード（Phase 1 ＝ 外部カード手動登録）。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §5.2
 *
 * <h2>暗号化方針</h2>
 * <p>個人を識別しうるカード番号本体・表示名・ニックネーム・メモは
 * {@link EncryptedStringConverter}（AES-256-GCM）で透過的に暗号化する。
 * 一方で {@code last4}（下 4 桁）と {@code barcode_format} はカードプレビュー描画と
 * フォーム選択に必要なため平文で保持する。
 *
 * <h2>プロバイダー紐付け</h2>
 * <p>{@code provider_id} は運営マスタ {@link PointCardProviderEntity} を弱参照する。
 * fuzzy match に失敗すると null となり、UI 側では「カテゴリ未分類」として表示する。
 * プロバイダーレコードが削除された場合は DDL の {@code ON DELETE SET NULL} で
 * カードは残し、ユーザーが手動で再紐付けできる状態にする。
 *
 * <h2>Phase 2 用カラム</h2>
 * <p>{@code balance} / {@code stamp_count} は Phase 2（自店発行カード）で使うため
 * 後付け ALTER を避けて最初から定義する。Phase 1 では常に NULL。
 *
 * <h2>原則準拠</h2>
 * <ul>
 *   <li>CLAUDE.md 原則 6 — UUIDv7 PK ({@link UuidV7Entity} 継承)</li>
 *   <li>個人スコープのため {@code AbstractUserOwnedRepository} を採用</li>
 *   <li>{@code provider_id} はドメイン内なので FK あり（{@code ON DELETE SET NULL}）</li>
 *   <li>GDPR — クラスに {@link PersonalData} を付与し収集対象に登録</li>
 * </ul>
 */
@Entity
@Table(name = "user_point_cards")
@PersonalData(category = "point_cards")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class UserPointCardEntity extends UuidV7CharEntity {

    /** 保有ユーザー ID（users.id）。FK あり ON DELETE CASCADE は同一物理的ライフサイクル前提。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 紐付いた運営プロバイダー ID（point_card_providers.id）。
     * fuzzy match で解決できなかった場合は null。プロバイダー側削除時は SET NULL。
     */
    @Column(name = "provider_id", columnDefinition = "CHAR(36)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID providerId;

    /** カードのユーザー表示名（暗号化）。識別必須のため一覧でも復号して返却する。 */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "display_name", nullable = false, columnDefinition = "VARBINARY(1024)")
    private String displayName;

    /** ユーザー任意のニックネーム（暗号化、optional）。 */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "nickname", columnDefinition = "VARBINARY(1024)")
    private String nickname;

    /** バーコードに変換されるカード番号本体（暗号化、必須）。 */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "barcode_value", nullable = false, columnDefinition = "VARBINARY(1024)")
    private String barcodeValue;

    /** バーコード形式。提示モードでの描画に必須なので平文保持。 */
    @Column(name = "barcode_format", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private BarcodeFormat barcodeFormat = BarcodeFormat.CODE128;

    /** カード番号下 4 桁（平文）。一覧プレビューと提示直前確認に使用する。 */
    @Column(name = "last4", length = 4)
    private String last4;

    /** ユーザーメモ（暗号化、optional）。 */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "memo", columnDefinition = "VARBINARY(2048)")
    private String memo;

    /** お気に入りフラグ。boolean プリミティブ採用で {@code isFavorite()} 自動生成。 */
    @Column(name = "is_favorite", nullable = false)
    @Builder.Default
    private boolean favorite = false;

    /** 同一お気に入り内での表示順（昇順）。 */
    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;

    /** Phase 2 用: 残高。Phase 1 は常に NULL。 */
    @Column(name = "balance", precision = 12, scale = 2)
    private BigDecimal balance;

    /** Phase 2 用: スタンプ数。Phase 1 は常に NULL。 */
    @Column(name = "stamp_count")
    private Integer stampCount;

    /** 最終利用日時（{@code POST /used} で更新）。 */
    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.barcodeFormat == null) {
            this.barcodeFormat = BarcodeFormat.CODE128;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
