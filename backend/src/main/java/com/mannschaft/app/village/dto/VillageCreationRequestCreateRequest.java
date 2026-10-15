package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.enums.VillageType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/**
 * 村作成申請の作成リクエスト（F17.1 Phase 1 B5）。
 *
 * <p>{@code guidelineAgreedAt} はクライアントがガイドラインに同意したタイムスタンプ。
 * サーバ側で「直近1時間以内」であることを必須化する（VILLAGE_015）。</p>
 *
 * @param name 村名（1〜100字）
 * @param slug 半角英数字とハイフンのみ（1〜64字）
 * @param category 任意カテゴリ
 * @param purpose 申請理由（必須）
 * @param guidelineAgreedAt ガイドライン同意時刻（直近1時間以内であること）
 * @param type 村種別。一般ユーザーは COMMUNITY のみ可（OFFICIAL は VILLAGE_028）
 * @param guidelineMd 任意の独自ガイドライン Markdown
 */
public record VillageCreationRequestCreateRequest(
        @NotBlank
        @Size(max = 100)
        String name,

        @NotBlank
        @Size(min = 1, max = 64)
        @Pattern(regexp = "^[a-z0-9-]+$", message = "slugは半角英数字とハイフンのみ使用できます")
        String slug,

        @Size(max = 64)
        String category,

        @NotBlank
        String purpose,

        @NotNull
        OffsetDateTime guidelineAgreedAt,

        @NotNull
        VillageType type,

        String guidelineMd
) {
}
