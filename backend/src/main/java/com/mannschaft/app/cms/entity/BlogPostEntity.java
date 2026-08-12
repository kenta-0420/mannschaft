package com.mannschaft.app.cms.entity;

import com.mannschaft.app.cms.PostPriority;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.PostType;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * ブログ記事・お知らせエンティティ。
 */
@Entity
@Table(name = "blog_posts")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class BlogPostEntity extends BaseEntity {

    private Long teamId;

    private Long organizationId;

    private Long userId;

    private Long socialProfileId;

    private Long authorId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 200)
    private String slug;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(length = 500)
    private String excerpt;

    @Column(length = 500)
    private String coverImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @Builder.Default
    private PostType postType = PostType.BLOG;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private Visibility visibility = Visibility.MEMBERS_ONLY;

    /** カスタム公開範囲テンプレートID (F01.7)。visibility = CUSTOM_TEMPLATE の場合のみ使用 */
    @Column(name = "visibility_template_id")
    private Long visibilityTemplateId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private PostPriority priority = PostPriority.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PostStatus status = PostStatus.DRAFT;

    private LocalDateTime publishedAt;

    private LocalDateTime selfReviewDeadline;

    private LocalDateTime archiveAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean pinned = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean allowComments = false;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String targetType = "ALL";

    private Long targetId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean crossPostToTimeline = false;

    private Long timelinePostId;

    @Column(length = 500)
    private String rejectionReason;

    @Column(nullable = false)
    @Builder.Default
    private Integer viewCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Short readingTimeMinutes = 0;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    /**
     * F19.1 Phase 2: 投稿時の本名スナップショット。
     * 投稿者が属するチーム/組織の supporter_name_disclosure = REAL_NAME の場合のみ格納する。
     * DISPLAY_NAME モード時は NULL。
     */
    @Column(name = "author_real_name_snapshot", length = 100)
    private String authorRealNameSnapshot;

    /**
     * F19.1 Phase 2: 投稿の公開表示フラグ。
     * false の場合、公開ページ・sitemap・OGP から除外する（ログイン後の通常ビューには変化なし）。
     */
    @Column(name = "public_visible", nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT TRUE")
    @Builder.Default
    private boolean publicVisible = true;

    @Column(length = 64)
    private String previewToken;

    private LocalDateTime previewTokenExpiresAt;

    private Long seriesId;

    private Short seriesOrder;

    private LocalDateTime deletedAt;

    /**
     * 記事のタイトル・本文・要約を更新する。
     */
    public void update(String title, String slug, String body, String excerpt,
                       String coverImageUrl, Visibility visibility, PostPriority priority,
                       Short readingTimeMinutes) {
        this.title = title;
        this.slug = slug;
        this.body = body;
        this.excerpt = excerpt;
        this.coverImageUrl = coverImageUrl;
        this.visibility = visibility;
        this.priority = priority;
        this.readingTimeMinutes = readingTimeMinutes;
    }

    /**
     * 公開ステータスを変更する。
     *
     * <p><b>DRAFT へ戻す遷移では公開日時（{@code published_at}）を破棄する</b>
     * （issue #2616 の回帰対策・AC-18/19）。予約公開バッチ
     * {@code BlogScheduledPublishBatchService} の走査条件は
     * 「{@code status = DRAFT} かつ {@code published_at <= 現在時刻}」であり、
     * 過去の公開日時を残したまま DRAFT へ戻すと、意図的に非公開化した記事が
     * 「公開時刻を過ぎた予約記事」と区別できず毎分のバッチに再公開されてしまう。
     * 仕様 {@code docs/features/F06.1_cms_blog.md} が定める
     * 「{@code published_at} が NULL ＝ 予約なし」の意味論に揃え、
     * 遷移そのものの中で不変条件を保証する（呼び出し側に委ねない）。</p>
     *
     * <p>ただし<b>未来の {@code published_at}（＝予約中）は消してはならない</b>。
     * 予約中の記事は DRAFT のまま未来時刻を持つのが正常な状態であり、
     * DRAFT → DRAFT の再保存やセルフレビューでの差し戻しで予約が失われては本末転倒である。
     * したがって破棄するのは「過去または現在時刻の公開日時」だけに限定する。</p>
     *
     * <p>ARCHIVED など DRAFT 以外への遷移では公開日時を保持する。アーカイブは
     * 「公開した事実を残したまま一覧から下げる」操作であり公開日時は履歴として意味を持つうえ、
     * バッチは {@code status = DRAFT} しか走査しないため再公開の危険もない。
     * ARCHIVED から DRAFT へ戻す場合は本メソッドの DRAFT 分岐で公開日時が破棄されるため、
     * 経路が増えても不変条件は漏れない。</p>
     */
    public void changeStatus(PostStatus newStatus) {
        if (newStatus == PostStatus.DRAFT) {
            unpublish();
            return;
        }
        this.status = newStatus;
    }

    /**
     * 公開状態を解除して下書きへ戻す（issue #2616 の回帰対策）。
     *
     * <p>過去の公開日時を残したまま DRAFT へ戻すと予約公開バッチに再公開されるため、
     * 「既に公開時刻が到来している公開日時」は NULL に消す。
     * 未来の公開日時は予約設定そのものなので維持する（AC-20）。</p>
     */
    public void unpublish() {
        this.status = PostStatus.DRAFT;
        if (this.publishedAt != null && !this.publishedAt.isAfter(LocalDateTime.now())) {
            this.publishedAt = null;
        }
    }

    /**
     * 公開日時を設定する（予約公開の判定を含む・issue #2616 / F06.1 §2210-2226）。
     *
     * <p><b>公開日時が未来なら「予約」であり、即時公開してはならない。</b>
     * 予約中の記事は {@code status = DRAFT} のまま {@code published_at} に未来時刻を持つ
     * （{@code PostStatus.SCHEDULED} は新設しない）。公開系クエリはすべて
     * {@code status = PUBLISHED} の等値判定であるため、予約中記事は
     * 「まだ DRAFT だから公開系に出ない」という構造的な理由で漏れない。
     * 公開時刻に達した記事は {@code BlogScheduledPublishBatchService} が
     * {@link #completeScheduledPublish()} で {@code PUBLISHED} へ遷移させる。</p>
     *
     * <p>プレビュートークンは<b>実際に公開した場合のみ</b>破棄する。予約中の記事は
     * まだ公開前でありレビュー用のプレビュー URL が生き続ける必要があるため、
     * 予約設定でトークンを消してはならない。</p>
     *
     * @param publishedAt 公開日時（{@code null} なら現在時刻＝即時公開）
     */
    public void publish(LocalDateTime publishedAt) {
        LocalDateTime effectiveAt = publishedAt != null ? publishedAt : LocalDateTime.now();
        this.publishedAt = effectiveAt;

        if (effectiveAt.isAfter(LocalDateTime.now())) {
            // 予約公開: 公開時刻まで DRAFT に留め置き、バッチの遷移を待つ。
            this.status = PostStatus.DRAFT;
            return;
        }

        this.status = PostStatus.PUBLISHED;
        this.previewToken = null;
        this.previewTokenExpiresAt = null;
    }

    /**
     * 予約公開バッチが公開時刻に達した記事を公開へ遷移させる（issue #2616）。
     *
     * <p>{@code published_at} は<b>ユーザーが指定した予約時刻のまま保持する</b>
     * （バッチ実行時刻で上書きしない。最大 1 分の実行遅延が公開日時に混入するのを防ぐ）。</p>
     */
    public void completeScheduledPublish() {
        this.status = PostStatus.PUBLISHED;
        this.previewToken = null;
        this.previewTokenExpiresAt = null;
    }

    /**
     * 却下する。
     */
    public void reject(String rejectionReason) {
        this.status = PostStatus.REJECTED;
        this.rejectionReason = rejectionReason;
    }

    /**
     * セルフレビューに遷移する。
     */
    public void pendingSelfReview(LocalDateTime deadline) {
        this.status = PostStatus.PENDING_SELF_REVIEW;
        this.selfReviewDeadline = deadline;
    }

    /**
     * 閲覧数をインクリメントする。
     */
    public void incrementViewCount() {
        this.viewCount++;
    }

    /**
     * プレビュートークンを設定する。
     */
    public void setPreviewToken(String token, LocalDateTime expiresAt) {
        this.previewToken = token;
        this.previewTokenExpiresAt = expiresAt;
    }

    /**
     * F19.1 Phase 7: public_visible フラグを更新する。
     *
     * <p>投稿者本人のみ操作可能（権限チェックは BlogPostService 層で実施）。</p>
     *
     * @param visible true=公開ページに表示する / false=非表示にする
     */
    public void updatePublicVisible(boolean visible) {
        this.publicVisible = visible;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
