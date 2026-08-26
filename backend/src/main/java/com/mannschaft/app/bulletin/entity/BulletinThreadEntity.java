package com.mannschaft.app.bulletin.entity;

import com.mannschaft.app.bulletin.Priority;
import com.mannschaft.app.bulletin.ReadTrackingMode;
import com.mannschaft.app.bulletin.ScopeType;
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
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 掲示板スレッドエンティティ。スレッドの本文・状態・統計情報を管理する。
 *
 * <p>F17.1 Phase 1: 村スコープ対応カラム ({@code scope_village_id} / {@code posted_as_subject_*})
 * を追加。{@code scopeType=VILLAGE} のときに {@code scopeVillageId} を使用する。
 * 投稿主体（チーム/組織代表）の指定は {@code postedAsSubjectType} + {@code postedAsSubjectId} で行う。
 * デフォルトは USER（個人投稿）。</p>
 */
@Entity
@Table(name = "bulletin_threads")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class BulletinThreadEntity extends BaseEntity {

    /**
     * カテゴリID。
     *
     * <p>設計書 F05.1 §3 に従い NULL 許容。カテゴリ削除時の既存スレッドや、
     * システム生成スレッド（アンケート等の自動作成）では NULL となる場合がある。</p>
     */
    @Column(nullable = true)
    private Long categoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    /**
     * 村スコープ ID（F17.1 Phase 1）。
     * {@code scopeType=VILLAGE} の場合に村の UUIDv7 を保持する。FK は張らない（原則1）。
     */
    @Column(name = "scope_village_id", columnDefinition = "BINARY(16)")
    private UUID scopeVillageId;

    private Long authorId;

    /**
     * 投稿主体種別（F17.1 Phase 1）。
     * USER（個人投稿）/ TEAM（チーム代表）/ ORGANIZATION（組織代表）。
     * デフォルトは USER。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "posted_as_subject_type", nullable = false, length = 20)
    @Builder.Default
    private com.mannschaft.app.village.entity.enums.VillageSubjectType postedAsSubjectType =
            com.mannschaft.app.village.entity.enums.VillageSubjectType.USER;

    /**
     * 投稿主体 ID（F17.1 Phase 1）。USER 以外の場合のみ値を持つ。FK は張らない（原則1）。
     */
    @Column(name = "posted_as_subject_id")
    private Long postedAsSubjectId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Priority priority = Priority.INFO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ReadTrackingMode readTrackingMode = ReadTrackingMode.COUNT_ONLY;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isPinned = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isLocked = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isArchived = false;

    /**
     * 保管庫フォルダ（{@code bulletin_archive_folders.id}）。
     *
     * <p>NULL かつ {@code isArchived=TRUE} = 保管庫直下（未分類）。{@code isArchived=FALSE} のときは
     * 無視（NULL 運用）。スレッドは 1 フォルダのみ所属。FK は張らない（bulletin 慣習・原則1）。</p>
     */
    @Column(name = "archive_folder_id", columnDefinition = "BINARY(16)")
    private UUID archiveFolderId;

    @Column(nullable = false)
    @Builder.Default
    private Integer replyCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer readCount = 0;

    private LocalDateTime lastRepliedAt;

    @Column(length = 30)
    private String sourceType;

    private Long sourceId;

    private LocalDateTime deletedAt;

    /**
     * スレッドのタイトルと本文を更新する。
     *
     * @param title    タイトル
     * @param body     本文
     * @param priority 優先度
     */
    public void update(String title, String body, Priority priority) {
        this.title = title;
        this.body = body;
        this.priority = priority;
    }

    /**
     * ピン留め状態を切り替える。
     */
    public void togglePin() {
        this.isPinned = !this.isPinned;
    }

    /**
     * ピン留め状態を明示値で設定する（F17.1 村掲示板グローバル方式 set 方式）。
     *
     * @param pinned 設定するピン留め状態
     */
    public void setPinned(boolean pinned) {
        this.isPinned = pinned;
    }

    /**
     * ロック状態を切り替える。
     */
    public void toggleLock() {
        this.isLocked = !this.isLocked;
    }

    /**
     * ロック状態を明示値で設定する（F17.1 村掲示板グローバル方式 set 方式）。
     *
     * @param locked 設定するロック状態
     */
    public void setLocked(boolean locked) {
        this.isLocked = locked;
    }

    /**
     * アーカイブする（設計書 F05.1 §4）。
     */
    public void archive() {
        this.isArchived = true;
    }

    /**
     * アーカイブを解除する（設計書 F05.1 §4: is_archived 双方向）。
     *
     * <p>一覧へ戻したスレッドにフォルダ所属を残さないため、{@code archiveFolderId} も NULL に
     * リセットする（設計書 §4 アーカイブ拡張）。</p>
     */
    public void unarchive() {
        this.isArchived = false;
        this.archiveFolderId = null;
    }

    /**
     * 保管庫フォルダを割り当てる（設計書 F05.1 §4 / §5 振り分け）。
     *
     * @param archiveFolderId 振り分け先フォルダ ID（NULL = 保管庫直下）
     */
    public void assignArchiveFolder(UUID archiveFolderId) {
        this.archiveFolderId = archiveFolderId;
    }

    /**
     * 保管庫フォルダ所属をクリアする（保管庫直下 = 未分類へ退避）。
     */
    public void clearArchiveFolder() {
        this.archiveFolderId = null;
    }

    /**
     * 返信カウントをインクリメントし、最終返信日時を更新する。
     */
    public void incrementReplyCount() {
        this.replyCount++;
        this.lastRepliedAt = LocalDateTime.now();
    }

    /**
     * 返信カウントをデクリメントする。
     */
    public void decrementReplyCount() {
        if (this.replyCount > 0) {
            this.replyCount--;
        }
    }

    /**
     * 既読カウントをインクリメントする。
     */
    public void incrementReadCount() {
        this.readCount++;
    }

    /**
     * 書き込み可能かどうかを判定する。
     *
     * @return ロック・アーカイブされていない場合 true
     */
    public boolean isWritable() {
        return !this.isLocked && !this.isArchived;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
