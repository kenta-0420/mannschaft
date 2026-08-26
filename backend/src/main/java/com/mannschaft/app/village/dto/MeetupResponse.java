package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageMeetupEntity;
import com.mannschaft.app.village.entity.enums.VillageMeetupStatus;
import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * F17.1 Phase 3-β — 寄合レスポンス。
 *
 * <p>{@code candidateDates} はネストされた候補日一覧。詳細取得時のみ詰める。
 * 一覧取得時は省略可（null）。</p>
 */
@Builder
public record MeetupResponse(
        UUID id,
        UUID villageId,
        String title,
        String description,
        Long organizerUserId,
        VillageMeetupStatus status,
        LocalDate confirmedDate,
        LocalTime confirmedTime,
        String location,
        String decisionsNote,
        // F17.2 追補: 定員（null=無制限）・GOING 実数・残席（capacity - goingCount／capacity=null なら null）。
        Integer capacity,
        long goingCount,
        Integer remainingSlots,
        LocalDateTime createdAt,
        List<MeetupCandidateDateResponse> candidateDates) {

    /**
     * 後方互換の生成メソッド（goingCount 未供給＝暫定 0）。
     *
     * <p><strong>試練フェーズの骨格</strong>: goingCount を 0 固定で通す。実カウントの結線
     * （出欠 GOING 数の供給）と remainingSlots の実値計算は出陣フェーズで行う。既存呼び出し側は
     * 本メソッド経由で 0 を渡すため、読み出し系 AC（AC-4/5/19）は現時点では red になる。</p>
     */
    public static MeetupResponse of(VillageMeetupEntity entity, List<MeetupCandidateDateResponse> candidateDates) {
        return of(entity, candidateDates, 0L);
    }

    /**
     * 定員・GOING 実数・残席を載せて生成する（F17.2 追補）。
     *
     * <p>remainingSlots は capacity が null なら null（無制限）、そうでなければ
     * {@code max(0, capacity - goingCount)}。定員縮小で goingCount が capacity を上回っても
     * 負値にはせず 0 に丸める（既存 GOING は保持しつつ以後の新規 GOING を塞ぐ意味）。</p>
     */
    public static MeetupResponse of(VillageMeetupEntity entity,
                                    List<MeetupCandidateDateResponse> candidateDates,
                                    long goingCount) {
        Integer capacity = entity.getCapacity();
        Integer remaining = (capacity == null)
                ? null
                : Math.max(0, capacity - (int) goingCount);
        return MeetupResponse.builder()
                .id(entity.getId())
                .villageId(entity.getVillageId())
                .title(entity.getTitle())
                .description(entity.getDescription())
                .organizerUserId(entity.getOrganizerUserId())
                .status(entity.getStatus())
                .confirmedDate(entity.getConfirmedDate())
                .confirmedTime(entity.getConfirmedTime())
                .location(entity.getLocation())
                .decisionsNote(entity.getDecisionsNote())
                .capacity(capacity)
                .goingCount(goingCount)
                .remainingSlots(remaining)
                .createdAt(entity.getCreatedAt())
                .candidateDates(candidateDates)
                .build();
    }
}
