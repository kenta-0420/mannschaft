package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.enums.VillageFestivalRsvpStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * F17.2 Wave2 ③お祭りの参加表明（RSVP）upsert リクエスト（設計書 §5.6）。
 *
 * <p>{@code status} は {@link VillageFestivalRsvpStatus}（GOING / MAYBE のみ）。
 * <b>ABSENT は enum に存在しない</b>ため、{@code "ABSENT"} を送ると Jackson の
 * enum バインドで 400 になる（欠席を保存させない設計・§10 ガードレール）。
 * {@code roleLabel} は役割の自由記述（任意・最大 60 文字・§5.3）。</p>
 */
public record FestivalRsvpUpsertRequest(
        @NotNull VillageFestivalRsvpStatus status,
        @Size(max = 60) String roleLabel) {
}
