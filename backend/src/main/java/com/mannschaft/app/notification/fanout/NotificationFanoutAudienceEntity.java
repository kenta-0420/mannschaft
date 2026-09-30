package com.mannschaft.app.notification.fanout;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * fan-out の宛先集合の見出し（{@code ORGANIZATION_TEAMS} 戦略用。F01.2.1 §5.6）。
 *
 * <p>ジョブ行（{@link NotificationFanoutJob}）の {@code scope_ref} がこの主キー
 * {@code audience_snapshot_id} を UUID 文字列で指す。{@code notification_fanout_jobs} には列を足さない。
 * 主キーは告知（フィード ID）から決定的に導く UUID
 * （{@code UUID.nameUUIDFromBytes("F02.8:broadcast-audience:" + feedId)}）で、呼び出し側が
 * {@code id(...)} に渡す。{@link UuidV7Entity} は id が未設定のときだけ採番するので、決定的キーはそのまま保存される。
 * 同じ告知の二重 enqueue は主キーで 1 組に収束する。</p>
 *
 * <p>クロスドメイン FK は張らない（{@code organization_id} は organization ドメインへの論理参照）。
 * 見出しと宛先チーム（{@link NotificationFanoutAudienceTeamEntity}）は同一ドメインなので CASCADE を張る（DDL 側）。
 * 1 つの集合を複数のジョブ行（親と子シャード）が参照するため、ジョブ行からの FK は張らない。</p>
 */
@Entity
@Table(name = "notification_fanout_audiences")
@AttributeOverride(name = "id", column = @Column(name = "audience_snapshot_id", columnDefinition = "BINARY(16)"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder
public class NotificationFanoutAudienceEntity extends UuidV7Entity {

    /** 宛先の組織 ID（直属メンバーの解決と加盟の再確認に使う）。 */
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** 作成した瞬間（UTC の Instant）。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
