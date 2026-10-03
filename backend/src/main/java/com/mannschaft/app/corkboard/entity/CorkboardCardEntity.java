package com.mannschaft.app.corkboard.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.AssertTrue;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * コルクボードカードエンティティ。ボード上の各カード（参照・メモ・URL・見出し）を管理する。
 */
@Entity
@Table(name = "corkboard_cards")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class CorkboardCardEntity extends BaseEntity {

    @Column(nullable = false)
    private Long corkboardId;

    /**
     * F09.8 積み残し件1: カードが現在所属している主セクション ID（V9.097 で追加）。
     * <p>{@code corkboard_card_groups} 中間テーブルは並行運用で残置するが、
     * primary section のみを持つ MVP 仕様ではこの列を真値とする。</p>
     */
    @Column(name = "section_id")
    private Long sectionId;

    @Column(nullable = false, length = 20)
    private String cardType;

    @Column(length = 30)
    private String referenceType;

    private Long referenceId;

    /**
     * F09.15/16 S0: UUIDv7 主キー reference 系の参照 ID（V66.001 で追加）。
     *
     * <p>F00 設計書 §3.4 (F00-A 案) に基づく並列カラム。
     * {@link com.mannschaft.app.common.visibility.ReferenceType#idKind()}
     * が {@code UUID_V7} を返す reference_type のときに本カラムを使う。</p>
     *
     * <p><strong>XOR 制約 (DB レイヤ + アプリレイヤの二重防御)</strong>:
     * {@code reference_id} と {@code reference_id_uuid} は両方 NOT NULL に
     * なってはならない。「両方 NULL」は許容（純メモ / URL / HEADING カード）。</p>
     */
    @Column(name = "reference_id_uuid", columnDefinition = "BINARY(16)")
    private UUID referenceIdUuid;

    /**
     * F09.15/16 S0: XOR 制約のアプリレイヤ検証。
     *
     * <p>DB 側で {@code chk_cc_reference_xor} を持つが、Hibernate flush 前の
     * Service 層バリデーションで早期に弾けるよう {@code @AssertTrue} で二重防御する。</p>
     *
     * @return 不正な状態（両方 NOT NULL）でなければ true
     */
    @Transient
    @AssertTrue(message = "reference_id と reference_id_uuid は両方を同時に設定できない")
    public boolean isReferenceIdXorValid() {
        return referenceId == null || referenceIdUuid == null;
    }

    @Column(columnDefinition = "TEXT")
    private String contentSnapshot;

    @Column(length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(length = 2000)
    private String url;

    @Column(length = 200)
    private String ogTitle;

    @Column(length = 500)
    private String ogImageUrl;

    @Column(length = 500)
    private String ogDescription;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String colorLabel = "NONE";

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String cardSize = "MEDIUM";

    @Column(name = "position_x", nullable = false)
    @Builder.Default
    private Integer positionX = 0;

    @Column(name = "position_y", nullable = false)
    @Builder.Default
    private Integer positionY = 0;

    @Column(name = "z_index", nullable = false)
    @Builder.Default
    private Integer zIndex = 0;

    @Column(columnDefinition = "TEXT")
    private String userNote;

    /**
     * F09.8 件3': ピン止め時付箋メモの専用色（V9.098 で追加）。
     * <p>{@code null} の場合はカラーラベル ({@link #colorLabel}) と同色とみなす。
     * 値ありはピン時に明示的に選択された付箋色（YELLOW / BLUE / GREEN / RED / PURPLE / GRAY 等）。</p>
     */
    @Column(name = "note_color", length = 20)
    private String noteColor;

    private LocalDateTime autoArchiveAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isArchived = false;

    @Column(name = "is_pinned", nullable = false)
    @Builder.Default
    private Boolean isPinned = false;

    @Column(name = "pinned_at")
    private LocalDateTime pinnedAt;

    @Column(name = "is_ref_deleted", nullable = false)
    @Builder.Default
    private Boolean isRefDeleted = false;

    @Column(nullable = false)
    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * カード情報を更新する。
     */
    public void update(String title, String body, String url, String colorLabel, String cardSize,
                       Integer positionX, Integer positionY, Integer zIndex, String userNote,
                       LocalDateTime autoArchiveAt) {
        this.title = title;
        this.body = body;
        this.url = url;
        this.colorLabel = colorLabel;
        this.cardSize = cardSize;
        this.positionX = positionX;
        this.positionY = positionY;
        this.zIndex = zIndex;
        this.userNote = userNote;
        this.autoArchiveAt = autoArchiveAt;
    }

    /**
     * カード位置を更新する。
     */
    public void updatePosition(Integer positionX, Integer positionY, Integer zIndex) {
        this.positionX = positionX;
        this.positionY = positionY;
        this.zIndex = zIndex;
    }

    /**
     * アーカイブ状態を変更する。
     */
    public void archive(boolean archived) {
        this.isArchived = archived;
    }

    /**
     * ピン止め状態を切り替える。
     * pin=true で {@code pinnedAt} を現在時刻に設定し、pin=false で {@code pinnedAt} を null に戻す。
     */
    public void pin(boolean pin) {
        this.isPinned = pin;
        this.pinnedAt = pin ? LocalDateTime.now() : null;
    }

    /**
     * F09.8 件3': ピン止め時の付箋メモと付箋色を上書きする。
     * <p>引数が {@code null} のフィールドは上書きしない（既存値を保持）。
     * アンピン時は触らない方針のため、本メソッドは pin=true 時のみ呼び出すこと。</p>
     *
     * @param userNote  付箋メモ本文（null なら既存値維持）
     * @param noteColor 付箋色（null なら既存値維持。値ありはカラーラベルと独立した明示色）
     */
    public void updatePinNote(String userNote, String noteColor) {
        if (userNote != null) {
            this.userNote = userNote;
        }
        if (noteColor != null) {
            this.noteColor = noteColor;
        }
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * OGP メタ情報を更新する（A-1 OGP 非同期取得バッチからの呼び出し用）。
     */
    public void updateOgpMeta(String ogTitle, String ogImageUrl, String ogDescription) {
        this.ogTitle = ogTitle;
        this.ogImageUrl = ogImageUrl;
        this.ogDescription = ogDescription;
    }

    /**
     * デッドリファレンス検知フラグを更新する。
     */
    public void markRefDeleted(boolean refDeleted) {
        this.isRefDeleted = refDeleted;
    }

    /**
     * 主セクション ID を更新する（F09.8 積み残し件1）。
     * セクション解除時は {@code null} を渡すこと。
     */
    public void assignSection(Long sectionId) {
        this.sectionId = sectionId;
    }
}
