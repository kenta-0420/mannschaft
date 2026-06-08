package com.mannschaft.app.match.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.match.domain.MatchEventType;
import com.mannschaft.app.match.domain.PeriodType;
import com.mannschaft.app.match.domain.TeamSide;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F08.10 時系列イベント（match ドメイン内・01 §B.2）。
 *
 * <p>UUIDv7（{@link UuidV7Entity} 継承）。<b>organization_id / deleted_at は持たない</b>
 * （テナント分離は親 matches・二段アクセス・01 §A.4・IDOR 根絶）。
 * {@code linked_event_id} は同一テーブル自己参照（DB 上 ON DELETE SET NULL）。クロスドメイン参照は ID のみ。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §B.2</p>
 */
@Entity
@Table(name = "match_events")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class MatchEventEntity extends UuidV7Entity {

    /** matches(id)（同一ドメイン・DB 上 FK CASCADE）。ID のみ保持し ORM 関連は張らない。 */
    @Column(name = "match_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID matchId;

    /** 経過分（タイマー連動・手動訂正可・NULL=分不明） */
    @Column(name = "minute")
    private Integer minute;

    /** アディショナルタイム（例 45+2 の "2"・NULL=なし） */
    @Column(name = "stoppage_minute")
    private Integer stoppageMinute;

    @Enumerated(EnumType.STRING)
    @Column(name = "period", nullable = false, length = 24)
    private PeriodType period;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 24)
    private MatchEventType eventType;

    /** 警告/退場の標準理由コード（競技別カタログの列挙値・非カード系は NULL・サッカーは C/S 系） */
    @Column(name = "card_reason_code", length = 8)
    private String cardReasonCode;

    /** event_type=OTHER 時の自由ラベル名 */
    @Column(name = "custom_label", length = 64)
    private String customLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "team_side", nullable = false, length = 16)
    private TeamSide teamSide;

    /** 主体選手（user ドメイン ID 参照・未登録は NULL・FK なし） */
    @Column(name = "player_user_id")
    private Long playerUserId;

    @Column(name = "player_name", length = 128)
    private String playerName;

    /** 背番号（未登録選手の同一性キーの一部） */
    @Column(name = "jersey_number")
    private Integer jerseyNumber;

    /** 関連選手（アシスト者/交代相手・user ドメイン ID 参照・FK なし） */
    @Column(name = "related_player_user_id")
    private Long relatedPlayerUserId;

    @Column(name = "related_player_name", length = 128)
    private String relatedPlayerName;

    /** 理由・メモ自由記述（入力検証・XSS/CRLF サニタイズ対象） */
    @Column(name = "note", length = 255)
    private String note;

    /** 時系列連鎖の相手イベント（同一テーブル自己参照・DB 上 ON DELETE SET NULL）。ID のみ保持。 */
    @Column(name = "linked_event_id", columnDefinition = "BINARY(16)")
    private UUID linkedEventId;

    /** 拡張属性（競技別の追加情報・JSON 文字列・最大 4KB・サーバー検証） */
    @Column(name = "detail", columnDefinition = "JSON")
    private String detail;

    /** 記録したチーム（共同記録の権限判定・team ドメイン ID 参照・NULL=記録係記録・FK なし） */
    @Column(name = "recorded_by_team_id")
    private Long recordedByTeamId;

    /** 同分内の表示順（タイムライン安定ソート） */
    @Column(name = "sort_seq", nullable = false)
    private int sortSeq;

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
