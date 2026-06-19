package com.mannschaft.app.pointcard.entity;

import com.mannschaft.app.common.entity.UuidV7CharEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * ポイントカードプロバイダー同義語辞書エンティティ。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §7.6
 *
 * <p>ユーザー入力の口語・略称・旧称をプロバイダーマスタに紐付けるため、
 * 1 つのプロバイダーに対して複数の {@code synonym_normalized}（正規化キー）を
 * 持たせる目的のテーブル。{@link com.mannschaft.app.pointcard.service.ProviderMatchService}
 * は起動時に本テーブルを全件読み込み、{@code synonym_normalized → provider} の
 * 完全一致ルックアップを構成する。
 *
 * <h2>正規化規則</h2>
 * <p>{@code synonym_normalized} は
 * {@link com.mannschaft.app.pointcard.service.ProviderMatchService#normalize(String)}
 * の出力と完全一致していなければならない（NFKC → カタカナ→ひらがな → 記号削除 → lower）。
 * 投入時はアプリケーション層で正規化計算を行い、UNIQUE(synonym_normalized) で重複を防ぐ。
 *
 * <h2>主キーと FK</h2>
 * <ul>
 *   <li>主キー: UUIDv7（CHAR(36) DDL に合わせ {@link UuidV7CharEntity} を継承）</li>
 *   <li>FK: {@code provider_id → point_card_providers.id ON DELETE CASCADE}
 *       — 同一ドメイン内なので CASCADE 許可（CLAUDE.md DB原則 2）</li>
 * </ul>
 */
@Entity
@Table(name = "point_card_provider_synonyms")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class PointCardProviderSynonymEntity extends UuidV7CharEntity {

    /** 紐付けるプロバイダーの id（point_card_providers.id）。 */
    @Column(name = "provider_id", nullable = false, length = 36)
    private UUID providerId;

    /** 表示用の同義語（例: 「ドコモポイント」「マツキヨ」）。 */
    @Column(name = "synonym_display", nullable = false, length = 100)
    private String synonymDisplay;

    /**
     * 正規化済みキー。
     * {@link com.mannschaft.app.pointcard.service.ProviderMatchService#normalize(String)}
     * の出力と完全一致していなければならない。UNIQUE 制約あり。
     */
    @Column(name = "synonym_normalized", nullable = false, length = 100, unique = true)
    private String synonymNormalized;

    /** 同義語の由来や種別を示す運営メモ（例: 「旧称」「略称」「関西略称」）。 */
    @Column(name = "memo", length = 200)
    private String memo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
