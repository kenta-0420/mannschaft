package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 歳時記カレンダーイベント作成リクエスト（F17.1 Phase 2 U4 §2.2）。
 *
 * <p>桃の節句・七夕・年越し等の年中行事登録に使う。
 * 作成者ユーザーIDは {@code SecurityUtils.getCurrentUserId()} から取得し本 DTO には含めない。</p>
 *
 * @param title              タイトル（必須・最大 100 文字）
 * @param description        詳細説明（任意）
 * @param eventDate          基準日（必須・{@code isAnnualRecurring=true} 時は年無視・月日のみ意味あり）
 * @param eventEndDate       終了日（任意・単日なら null）
 * @param isAnnualRecurring  毎年繰返すか（必須・true=年中行事 / false=単発）
 * @param iconEmoji          表示絵文字（任意・最大 20 文字）
 * @param colorHex           カレンダー表示色 #RRGGBB（任意）
 */
public record CalendarEventCreateRequest(
        @NotBlank @Size(max = 100) String title,
        @Size(max = 5000) String description,
        @NotNull LocalDate eventDate,
        LocalDate eventEndDate,
        @NotNull Boolean isAnnualRecurring,
        @Size(max = 20) String iconEmoji,
        @Size(max = 7) String colorHex
) {
}
