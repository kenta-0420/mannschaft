package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency;
import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueStatus;
import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueType;
import com.mannschaft.app.village.entity.enums.VillageNewsletterVisibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 村ニュースレター号エンティティ（F17.1 ②-1・案Y）。
 *
 * <p>「集計 → 凍結 → ラグ → 配信」を 1 つの号として持つ。案Y の pull 層（ためる／公開一覧）として
 * {@code title} / {@code visibility} を備える（タグは中間表 {@link VillageNewsletterIssueTagEntity}）。</p>
 *
 * <h2>改ざん不可（snapshot 凍結）の保証方法 — 設計書 §4.2</h2>
 * <p>集計値 {@code digest_*} は号の凍結後に書き換え不可でなければならない（村人が受け取る集計値は常に事実）。
 * 本エンティティは<b>クラスレベルの {@code @Setter} を付けず</b>、{@code digest_*} フィールドに
 * <b>setter／ミューテータを一切設けない</b>。ダイジェスト値は生成時（集計・凍結バッチ）に
 * {@code @SuperBuilder} 経由でのみ設定でき、以後は getter しか存在しない
 * （＝「更新経路が存在しない」＝ AC-02）。コメント欄 {@code headmanComment} は
 * ダイジェスト本体とは別カラムで、凍結後も {@link #updateComment} で編集できる（要件②）。
 * これは snapshot の不変性を侵さない。</p>
 *
 * <h2>原則準拠</h2>
 * <ul>
 *   <li>原則1: {@code comment_updated_by}（user_id）に FK を張らない。</li>
 *   <li>原則2: {@code village_id} の FK は村ドメイン内のため CASCADE 可（既存村テーブル群と同じ作法）。</li>
 *   <li>原則3: {@code deleted_at} で論理削除。</li>
 *   <li>原則6: 新規テーブルのため UUIDv7 を採用。</li>
 * </ul>
 */
@Entity
@Table(name = "village_newsletter_issues")
@Getter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageNewsletterIssueEntity extends UuidV7Entity {

    /** FK → villages.id（同一ドメイン CASCADE）。 */
    @Column(name = "village_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID villageId;

    /** どの設定に紐づく号か（village_newsletters.id）。号外は NULL。FK は張らない（archive を設定削除に巻き込まない）。 */
    @Column(name = "newsletter_id", columnDefinition = "BINARY(16)")
    private UUID newsletterId;

    /** WEEKLY / MONTHLY。EXTRA（号外）では NULL。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", length = 20)
    private VillageNewsletterFrequency frequency;

    /** REGULAR（定期便）/ EXTRA（号外）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "issue_type", nullable = false, length = 20)
    private VillageNewsletterIssueType issueType;

    /** ライフサイクル状態。凍結後はダイジェストが不変。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private VillageNewsletterIssueStatus status;

    // ---- pull 層（案Y・ためる／公開一覧） ---------------------------------

    /** 号のタイトル（既定は自動生成・村長が編集可）。 */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** 公開範囲（VILLAGE_MEMBERS / PUBLIC）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 30)
    private VillageNewsletterVisibility visibility;

    // ---- 集計対象期間（凍結時に確定・不変） --------------------------------

    @Column(name = "period_start", nullable = false)
    private LocalDateTime periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDateTime periodEnd;

    /** 集計・凍結を実施した時刻。 */
    @Column(name = "aggregated_at")
    private LocalDateTime aggregatedAt;

    /** 配信予定（ラグの終端）。 */
    @Column(name = "scheduled_publish_at")
    private LocalDateTime scheduledPublishAt;

    /** 実配信時刻。 */
    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    // ---- 凍結ダイジェスト snapshot（凍結後は不変・setter を設けない） --------

    @Column(name = "digest_post_count", nullable = false)
    private Integer digestPostCount;

    @Column(name = "digest_new_member_count", nullable = false)
    private Integer digestNewMemberCount;

    @Column(name = "digest_festival_count", nullable = false)
    private Integer digestFestivalCount;

    @Column(name = "digest_meetup_count", nullable = false)
    private Integer digestMeetupCount;

    @Column(name = "digest_recruit_count", nullable = false)
    private Integer digestRecruitCount;

    @Column(name = "digest_topic_1_name", length = 100)
    private String digestTopic1Name;

    @Column(name = "digest_topic_1_count", nullable = false)
    private Integer digestTopic1Count;

    @Column(name = "digest_topic_2_name", length = 100)
    private String digestTopic2Name;

    @Column(name = "digest_topic_2_count", nullable = false)
    private Integer digestTopic2Count;

    @Column(name = "digest_topic_3_name", length = 100)
    private String digestTopic3Name;

    @Column(name = "digest_topic_3_count", nullable = false)
    private Integer digestTopic3Count;

    // ---- 村長コメント（別欄・凍結後も編集可。号外では本文本体） --------------

    @Column(name = "headman_comment", columnDefinition = "TEXT")
    private String headmanComment;

    /** コメント最終更新者ユーザーID（FK 張らない・原則1）。 */
    @Column(name = "comment_updated_by")
    private Long commentUpdatedBy;

    @Column(name = "comment_updated_at")
    private LocalDateTime commentUpdatedAt;

    // ---- 監査 -----------------------------------------------------------

    /** 論理削除（原則3）。 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // ==================================================================
    // ドメイン操作（digest_* の書き換え経路は意図的に存在しない）
    // ==================================================================

    /**
     * 号を凍結する（AGGREGATED → FROZEN）。ダイジェスト値は生成時に builder で確定済みで、
     * ここでは状態遷移と時刻の確定のみを行う。二重凍結・凍結済みからの再凍結は改ざんに当たるため拒否する。
     *
     * @throws IllegalStateException すでに AGGREGATED でない場合（改ざん防止）
     */
    public void freeze(LocalDateTime aggregatedAt, LocalDateTime scheduledPublishAt) {
        if (this.status != VillageNewsletterIssueStatus.AGGREGATED) {
            throw new IllegalStateException(
                    "Newsletter issue digest is already frozen and cannot be re-frozen: status=" + this.status);
        }
        this.status = VillageNewsletterIssueStatus.FROZEN;
        this.aggregatedAt = aggregatedAt;
        this.scheduledPublishAt = scheduledPublishAt;
    }

    /** 村長コメントを保存する（ダイジェスト本体とは別欄。凍結後も編集可）。 */
    public void updateComment(String comment, Long userId, LocalDateTime at) {
        this.headmanComment = comment;
        this.commentUpdatedBy = userId;
        this.commentUpdatedAt = at;
    }

    /** タイトルを変更する（村長が編集可）。 */
    public void retitle(String title) {
        this.title = title;
    }

    /** 公開範囲を切り替える。 */
    public void changeVisibility(VillageNewsletterVisibility visibility) {
        this.visibility = visibility;
    }

    /** 配信完了にする（FROZEN → PUBLISHED）。 */
    public void markPublished(LocalDateTime at) {
        this.status = VillageNewsletterIssueStatus.PUBLISHED;
        this.publishedAt = at;
    }

    /** 号を取り消す（→ CANCELED）。 */
    public void cancel() {
        this.status = VillageNewsletterIssueStatus.CANCELED;
    }

    /** 論理削除する。 */
    public void softDelete(LocalDateTime at) {
        this.deletedAt = at;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
        if (this.issueType == null) {
            this.issueType = VillageNewsletterIssueType.REGULAR;
        }
        if (this.status == null) {
            this.status = VillageNewsletterIssueStatus.AGGREGATED;
        }
        if (this.visibility == null) {
            this.visibility = VillageNewsletterVisibility.VILLAGE_MEMBERS;
        }
        this.digestPostCount = zeroIfNull(this.digestPostCount);
        this.digestNewMemberCount = zeroIfNull(this.digestNewMemberCount);
        this.digestFestivalCount = zeroIfNull(this.digestFestivalCount);
        this.digestMeetupCount = zeroIfNull(this.digestMeetupCount);
        this.digestRecruitCount = zeroIfNull(this.digestRecruitCount);
        this.digestTopic1Count = zeroIfNull(this.digestTopic1Count);
        this.digestTopic2Count = zeroIfNull(this.digestTopic2Count);
        this.digestTopic3Count = zeroIfNull(this.digestTopic3Count);
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    private static Integer zeroIfNull(Integer v) {
        return v == null ? Integer.valueOf(0) : v;
    }
}
