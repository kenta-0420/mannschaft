package com.mannschaft.app.schedule.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.gdpr.PersonalData;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 予定コメントエンティティ（F03.16 予定コメントスレッド）。
 *
 * <p>設計書: {@code docs/features/F03.16_schedule_comment_thread.md} §3.3 / §3.6 / §1.6。</p>
 *
 * <h2>主キーが UUIDv7 である理由（§1.6）</h2>
 * <p>新規テーブルは原則6（{@code UuidV7Entity} 継承）に従う。{@code ArchUnit} 番人
 * {@code EntityUuidV7ConventionArchTest} が新規 {@code @Entity} に強制しており、
 * 凍結ストアへの新規追記は禁止されている（既存違反の「正規登録」であり緩めてはならない）。</p>
 *
 * <h2>schedule_id / user_id が BIGINT である理由（§1.6）</h2>
 * <p>外部参照列の型は参照先（親）に合わせる。{@code schedules.id} / {@code users.id} は
 * いずれも BIGINT のため、本エンティティの主キーが UUID であっても参照列は {@code Long} のまま
 * （前例: {@code NotificationFanoutJob}）。</p>
 *
 * <h2>{@code @SQLRestriction} を付けない理由（§3.3・意図的に金型から外す点）</h2>
 * <p>本機能はトゥームストーン（削除済みだが生存返信があれば表示する行）が一覧の本流であり、
 * {@code @SQLRestriction("deleted_at IS NULL")} を付けると全クエリへ問答無用でその条件が
 * 差し込まれ、主要ユースケースと正面衝突する。{@link com.mannschaft.app.circulation.entity.CirculationCommentEntity}
 * （金型）は返信ツリーもトゥームストーンも持たないため同じ問題が起きず、本エンティティのみ
 * 意図的にこの点を外す（検分で必ず指摘されるためこの Javadoc を根拠として残す）。
 * 各リポジトリメソッドが {@code deleted_at} を明示条件で扱う規律を守ること。</p>
 *
 * <h2>GDPR / 個人データ</h2>
 * <p>{@code @PersonalData(category = "scheduleComments")} を付与する。対応する
 * {@code PersonalDataCollector} 側の収集処理・{@code category} 登録キーとの一致は
 * 後続隊（Service 実装隊）の責務であり、本クラスでは注釈のみを付与する（§3.3）。</p>
 */
@PersonalData(category = "scheduleComments")
@Entity
@Table(name = "schedule_comments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ScheduleCommentEntity extends UuidV7Entity {

    /** 親スケジュール（同一ドメイン内 FK・ON DELETE CASCADE）。参照先に合わせ BIGINT。 */
    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    /** 投稿者。FK は張らない（原則1・クロスドメイン）。退会匿名化で NULL 化されうる。 */
    @Column(name = "user_id")
    private Long userId;

    /** 返信先コメント ID（NULL = トップレベル）。自表参照のため UUID。 */
    @Column(name = "parent_id", columnDefinition = "BINARY(16)")
    @JdbcTypeCode(SqlTypes.BINARY)
    private UUID parentId;

    /** スレッド根の ID（トップレベルは NULL）。自表参照のため UUID。 */
    @Column(name = "root_id", columnDefinition = "BINARY(16)")
    @JdbcTypeCode(SqlTypes.BINARY)
    private UUID rootId;

    /** 階層。0 = トップレベル、1 = 返信（当面の上限。§3.3.1）。 */
    @Column(nullable = false)
    @Builder.Default
    private Integer depth = 0;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    /** 編集済みフラグ。 */
    @Column(name = "is_edited", nullable = false)
    @Builder.Default
    private Boolean isEdited = false;

    /**
     * 返信数の非正規化カウンタ（生存返信数）。トップレベル行のみが意味を持つ（返信行は常に 0）。
     * 減算は 0 を下限とするガードを必ず入れる（{@link #decrementReplyCount()}）。
     */
    @Column(name = "reply_count", nullable = false)
    @Builder.Default
    private Integer replyCount = 0;

    /**
     * {@code UuidV7Entity} は {@code id} しか持たないため、{@code BaseEntity} と異なり
     * {@code created_at}/{@code updated_at} は本クラスで明示的に宣言する（{@code NotificationFanoutJob} の作法）。
     * ソート契約の基準列（§1.6）なので欠落させない。
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 論理削除。{@code @SQLRestriction} は付けない（クラス Javadoc 参照）。 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * コメント本文を編集する。
     *
     * @param newBody 新しい本文
     */
    public void editBody(String newBody) {
        this.body = newBody;
        this.isEdited = true;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 削除済みかどうかを判定する。
     */
    public boolean isDeleted() {
        return this.deletedAt != null;
    }

    /**
     * 返信数をインクリメントする（トップレベル行に対して呼ぶ）。
     */
    public void incrementReplyCount() {
        this.replyCount++;
    }

    /**
     * 返信数をデクリメントする。0 未満にはならない（0 下限ガード。
     * {@link com.mannschaft.app.chat.entity.ChatMessageEntity#decrementReplyCount()} と同じ規律）。
     * 二重削除・再試行でカウンタが負に落ちるとトゥームストーン表示（§5.3）が破綻するため必須。
     */
    public void decrementReplyCount() {
        if (this.replyCount > 0) {
            this.replyCount--;
        }
    }

    /**
     * トップレベルコメント（depth == 0）かどうかを判定する。
     */
    public boolean isTopLevel() {
        return this.depth == 0;
    }
}
