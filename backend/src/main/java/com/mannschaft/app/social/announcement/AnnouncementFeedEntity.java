package com.mannschaft.app.social.announcement;

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
 * お知らせウィジェット横断フィードエンティティ（F02.6）。
 *
 * <p>
 * 投稿者が「お知らせウィジェットへ表示する」を ON にしたコンテンツを横断集約する
 * ポリモルフィックテーブル {@code announcement_feeds} のエンティティ。
 * </p>
 *
 * <p>
 * <b>ポリモルフィック参照</b>:
 * {@code scopeType + scopeId} でチームまたは組織を参照し、
 * {@code sourceType + sourceId} でブログ記事・掲示板スレッドなど元コンテンツを参照する。
 * FK 制約は張らず、削除連動は ApplicationEvent ベースで管理する（設計書 5.2 章）。
 * </p>
 *
 * <p>
 * <b>論理削除</b>: 本テーブルは論理削除を行わない。
 * 元コンテンツ削除時は {@code sourceDeletedAt} をセットしてウィジェット表示から除外し、
 * 90 日後にバッチで物理削除する（設計書 5.2 章）。
 * </p>
 *
 * <p>
 * <b>並び順</b>: ピン留め優先 → 優先度（URGENT → IMPORTANT → NORMAL）→ 新着順。
 * </p>
 */
@Entity
@Table(name = "announcement_feeds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class AnnouncementFeedEntity extends BaseEntity {

    /**
     * 表示スコープ種別（TEAM / ORGANIZATION）。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnnouncementScopeType scopeType;

    /**
     * 表示スコープの ID（teams.id または organizations.id）。
     */
    @Column(nullable = false)
    private Long scopeId;

    /**
     * 元コンテンツ種別（BLOG_POST / BULLETIN_THREAD / TIMELINE_POST / CIRCULATION_DOCUMENT / SURVEY）。
     * DB は VARCHAR(30)、Java 層でこの enum にマッピング。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AnnouncementSourceType sourceType;

    /**
     * 元コンテンツの ID（ポリモルフィック参照）。
     */
    @Column(nullable = false)
    private Long sourceId;

    /**
     * お知らせ表示フラグを付けた操作者（退会時は NULL に設定）。
     */
    @Column
    private Long authorId;

    /**
     * 表示用タイトル（元コンテンツから非正規化コピー。タイトルなしは本文先頭 30 文字）。
     */
    @Column(nullable = false, length = 200)
    private String titleCache;

    /**
     * 本文抜粋（非正規化コピー。リスト表示用 150 文字目安）。
     */
    @Column(length = 300)
    private String excerptCache;

    /**
     * お知らせ優先度（URGENT / IMPORTANT / NORMAL）。
     * 元コンテンツの優先度から正規化してセット（設計書 3 章）。
     */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String priority = "NORMAL";

    /**
     * ピン留めフラグ。ADMIN のみ変更可。スコープごと最大 5 件まで（Service 層制限）。
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isPinned = false;

    /**
     * ピン留め操作日時（ピン留め中の並び順用）。
     */
    @Column
    private LocalDateTime pinnedAt;

    /**
     * ピン留めした ADMIN のユーザー ID（退会時は NULL）。
     */
    @Column
    private Long pinnedBy;

    /**
     * 閲覧可能範囲（元コンテンツから継承・同期更新）。
     * 値: MEMBERS_ONLY / SUPPORTERS_AND_ABOVE / PUBLIC
     */
    @Column(nullable = false, length = 30)
    @Builder.Default
    private String visibility = "MEMBERS_ONLY";

    /**
     * 表示開始日時（NULL = 即時）。予約公開のブログ記事を事前登録する場合に使用。
     */
    @Column
    private LocalDateTime startsAt;

    /**
     * 表示終了日時（NULL = 期限なし）。アンケート締切・回覧期限で自動失効させる場合に使用。
     */
    @Column
    private LocalDateTime expiresAt;

    /**
     * 元コンテンツ削除検出日時。ApplicationEvent 経由でセット。
     * NULL 以外の行はウィジェット一覧から除外し、90 日後にバッチで物理削除する。
     */
    @Column
    private LocalDateTime sourceDeletedAt;

    // --- ドメインメソッド ---

    /**
     * お知らせをピン留めする。
     *
     * @param pinnedByUserId ピン留めした ADMIN のユーザー ID
     */
    public void markPinned(Long pinnedByUserId) {
        this.isPinned = true;
        this.pinnedAt = LocalDateTime.now();
        this.pinnedBy = pinnedByUserId;
    }

    /**
     * お知らせのピン留めを解除する。
     */
    public void unpin() {
        this.isPinned = false;
        this.pinnedAt = null;
        this.pinnedBy = null;
    }

    /**
     * 元コンテンツが削除されたことを記録する。
     * このレコードはウィジェット一覧から除外され、90 日後にバッチで物理削除される。
     */
    public void markSourceDeleted() {
        this.sourceDeletedAt = LocalDateTime.now();
    }

    /**
     * 元コンテンツの削除を取り消す（復元時に使用）。
     */
    public void restoreSource() {
        this.sourceDeletedAt = null;
    }

    /**
     * タイトルキャッシュと抜粋キャッシュを更新する（元コンテンツ更新時の同期用）。
     *
     * @param newTitleCache   新しいタイトルキャッシュ
     * @param newExcerptCache 新しい抜粋キャッシュ
     */
    public void updateCaches(String newTitleCache, String newExcerptCache) {
        this.titleCache = newTitleCache;
        this.excerptCache = newExcerptCache;
    }

    /**
     * 閲覧可能範囲を更新する（元コンテンツの visibility 変更時の同期用）。
     *
     * @param newVisibility 新しい visibility 値
     */
    public void updateVisibility(String newVisibility) {
        this.visibility = newVisibility;
    }

    /**
     * 優先度を更新する（元コンテンツの優先度変更時の同期用）。
     *
     * @param newPriority 新しい優先度値
     */
    public void updatePriority(String newPriority) {
        this.priority = newPriority;
    }
}
