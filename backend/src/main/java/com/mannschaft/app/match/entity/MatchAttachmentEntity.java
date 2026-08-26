package com.mannschaft.app.match.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F08.10 局面写真など match スコープの添付子表（盤上競技・match ドメイン内・01 §B.7 / 03 §C.7a）。
 *
 * <p>UUIDv7（{@link UuidV7Entity} 継承・原則6）。<b>organization_id / deleted_at は持たない</b>
 * （テナント分離は親 matches・二段アクセス・01 §A.4・IDOR 根絶）。子の削除は親 matches の CASCADE に従う。</p>
 *
 * <p>既存添付基盤（presign 方式・bulletin_attachments と同方式・SVG 除外・サイズ上限 10MB・IDOR 逆引き）の
 * 実装パターンを踏襲する（新規ストレージ機構は作らない）。{@code file_key} は server 採番
 * （クライアント任意 key を信用しない・マスアサインメント防止）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §B.7
 *   / 03_permissions_and_recording_modes.md §C.7a / sports/05_shogi.md §8.2</p>
 */
@Entity
@Table(name = "match_attachments")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class MatchAttachmentEntity extends UuidV7Entity {

    /** matches(id)（同一ドメイン・DB 上 FK CASCADE）。ID のみ保持し ORM 関連は張らない。 */
    @Column(name = "match_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID matchId;

    /** R2 オブジェクトキー（server 採番・生 key は外部に返さない）。 */
    @Column(name = "file_key", nullable = false, length = 512)
    private String fileKey;

    /** 元ファイル名（表示用）。 */
    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    /** MIME（画像のみ・SVG 除外）。 */
    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    /** バイト数（上限 10MB）。 */
    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /** アップロード者（user ドメイン ID 参照・FK なし）。 */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
