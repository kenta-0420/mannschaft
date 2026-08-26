package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.enums.VillageBulletinVisibility;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 村作成リクエスト DTO（F17.1 §4.1.1）。
 *
 * <p>運営権限による作成、または村作成申請承認後のシステム実行で使用される。
 * 一般ユーザーが API を直接叩いて {@code type=OFFICIAL} を作成しようとした場合、
 * Service 層で {@link com.mannschaft.app.village.VillageErrorCode#VILLAGE_CREATE_FORBIDDEN}
 * が返される。</p>
 *
 * @param slug         スラッグ（{@code ^[a-z0-9-]{3,40}$}・グローバル UNIQUE）
 * @param name         村名（1〜80 文字・グローバル UNIQUE）
 * @param description  説明文（任意・最大 2000 文字）
 * @param type         村種別（OFFICIAL / COMMUNITY）
 * @param joinPolicy   参加方式（FREE / APPROVAL）
 * @param visibility   可視性（PUBLIC / UNLISTED）
 * @param category           カテゴリ（任意・最大 40 文字）
 * @param bulletinVisibility 掲示板公開範囲（任意・null のとき Service で MEMBERS_ONLY 既定）
 * @param guidelineMd        ガイドライン Markdown（任意）
 */
public record VillageCreateRequest(
        @NotBlank
        @Pattern(regexp = "^[a-z0-9-]{3,40}$", message = "スラッグは英小文字・数字・ハイフン 3〜40 文字で指定してください")
        String slug,

        @NotBlank
        @Size(min = 1, max = 80)
        String name,

        @Size(max = 2000)
        String description,

        @NotNull
        VillageType type,

        @NotNull
        VillageJoinPolicy joinPolicy,

        @NotNull
        VillageVisibility visibility,

        @Size(max = 40)
        String category,

        VillageBulletinVisibility bulletinVisibility,

        String guidelineMd
) {}
