package com.mannschaft.app.social.announcement;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * グループ宛てお知らせの送信時スナップショット（F01.2.1 §5.6・§8.2）。
 *
 * <p>グループ宛てのお知らせを送った時点で、そのグループに ACTIVE で所属していたチームを
 * 「グループ単位」で固定して残す。グループが後から削除・改名されても、表示判定はこの行で行える。
 * {@code announcement_feeds} と同一ドメインなので {@code feed_id} に CASCADE の FK を張る（DDL 側。原則2）。
 * {@code group_id} はチームグループ（別ドメイン）の UUID 文字列で、FK は張らない（原則1）。</p>
 *
 * <p>DDL は {@code V234.__create_announcement_group_snapshots_and_fanout_audiences.sql}。
 * 設計書 §5.6 の複合主キーは、原則6（新規表は UuidV7Entity）のため {@code id} 主キー + UNIQUE に置き換えた。
 * 行数は「1 件のお知らせあたりの送信時の対象チーム数」で、件数上限は掛けない。</p>
 */
@Entity
@Table(
        name = "announcement_feed_group_snapshots",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_afgs_feed_group_team",
                columnNames = {"feed_id", "group_id", "team_id"}),
        indexes = @Index(name = "idx_afgs_team_feed", columnList = "team_id, feed_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class AnnouncementFeedGroupSnapshotEntity extends UuidV7Entity {

    /** {@code announcement_feeds.id}（同一ドメイン）。 */
    @Column(name = "feed_id", nullable = false)
    private Long feedId;

    /** 送信時に展開したチームグループ ID（UUID 文字列）。 */
    @Column(name = "group_id", nullable = false, columnDefinition = "CHAR(36)")
    private String groupId;

    /** 送信時点でそのグループに ACTIVE で所属していたチーム ID。 */
    @Column(name = "team_id", nullable = false)
    private Long teamId;

    /**
     * {@code feed_id} の FK（{@code fk_afgs_feed}・ON DELETE CASCADE）を Entity にも写すための読み取り専用の関連。
     *
     * <p>IT のスキーマは Flyway でなく Entity から Hibernate が作るため、ここで宣言しないと IT だけ
     * CASCADE が効かず本番とずれる（AC-H26）。値の書き込みは {@link #feedId} だけで行い、本関連は参照しない。</p>
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "feed_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_afgs_feed"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    @Getter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    private AnnouncementFeedEntity feed;
}
