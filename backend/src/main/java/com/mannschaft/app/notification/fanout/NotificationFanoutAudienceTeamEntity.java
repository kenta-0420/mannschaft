package com.mannschaft.app.notification.fanout;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * fan-out の宛先集合の中身（宛先チーム。F01.2.1 §5.6）。0 行でもよい（直属メンバーだけに届く告知）。
 *
 * <p>{@code audience_snapshot_id} は {@link NotificationFanoutAudienceEntity} の主キー（同一ドメイン・CASCADE は DDL 側）。
 * {@code team_id} はチームドメインへの論理参照（FK なし）。
 * 設計書 §5.6 の複合主キー {@code (audience_snapshot_id, team_id)} は、原則6（新規表は UuidV7Entity）のため
 * {@code id} 主キー + UNIQUE に置き換えた（検索経路と一意性は同じ）。</p>
 */
@Entity
@Table(
        name = "notification_fanout_audience_teams",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_nfat_audience_team",
                columnNames = {"audience_snapshot_id", "team_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder
public class NotificationFanoutAudienceTeamEntity extends UuidV7Entity {

    @Column(name = "audience_snapshot_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID audienceSnapshotId;

    @Column(name = "team_id", nullable = false)
    private Long teamId;
}
