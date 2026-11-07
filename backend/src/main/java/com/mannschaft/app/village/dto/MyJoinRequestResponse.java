package com.mannschaft.app.village.dto;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageJoinRequestEntity;
import com.mannschaft.app.village.entity.enums.VillageRequestStatus;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 認証本人の村参加申請履歴 1 件（CMP-260826-1456）。
 *
 * <p>{@link JoinRequestResponse} に、申請先の村を本人が識別するための {@code villageName} と
 * {@code villageState} を加えたもの。本人が自分で申請した村に限り（呼び出し元が requester で固定済み）
 * 名前を返すため、申請していない第三者には村の存在も名前も渡らない。</p>
 *
 * <p>村が後から論理削除（{@code deleted_at}）・凍結（{@code archived_at}）されても履歴は残し、
 * 名前は申請時点から変わらず返したうえで {@code villageState} で状態を示す。
 * 村の行が物理的に存在しない場合は {@code villageName=null}・{@code villageState=DELETED}。</p>
 */
public record MyJoinRequestResponse(
        UUID id,
        UUID villageId,
        String villageName,
        VillageState villageState,
        VillageSubjectType subjectType,
        Long subjectId,
        String message,
        VillageRequestStatus status,
        UUID reviewedBy,
        Instant reviewedAt,
        String reviewComment,
        Instant createdAt
) {

    /** 申請先の村の現在の状態。 */
    public enum VillageState {
        /** 通常。 */
        ACTIVE,
        /** 運営判断で凍結済み。 */
        ARCHIVED,
        /** 論理削除済み、または行が存在しない。 */
        DELETED
    }

    /**
     * @param village 申請先の村。行が存在しない場合は {@code null}
     */
    public static MyJoinRequestResponse of(VillageJoinRequestEntity e, VillageEntity village) {
        VillageState state;
        if (village == null || village.getDeletedAt() != null) {
            state = VillageState.DELETED;
        } else if (village.getArchivedAt() != null) {
            state = VillageState.ARCHIVED;
        } else {
            state = VillageState.ACTIVE;
        }
        return new MyJoinRequestResponse(
                e.getId(),
                e.getVillageId(),
                village == null ? null : village.getName(),
                state,
                e.getSubjectType(),
                e.getSubjectId(),
                e.getMessage(),
                e.getStatus(),
                e.getReviewerMembershipId(),
                toInstant(e.getReviewedAt()),
                e.getReviewComment(),
                toInstant(e.getCreatedAt())
        );
    }

    /**
     * Entity の壁時計（サーバー基準ゾーンで記録された {@link LocalDateTime}）を、起きた瞬間の {@link Instant} へ変換する。
     * 日時方針（docs/architecture/datetime_policy_utc_instant_vs_wallclock.md）に従い、DTO は瞬間型で返す。
     */
    private static Instant toInstant(LocalDateTime wallClock) {
        return wallClock == null ? null : wallClock.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant();
    }
}
