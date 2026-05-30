package com.mannschaft.app.bulletin.dto;

import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * グローバル方式スレッド作成リクエスト DTO（F17.1 村掲示板グローバル方式）。
 *
 * <p>FE は {@code POST /api/v1/bulletin/threads} の body（JSON）または multipart の {@code data} パートに
 * スコープ情報（{@code scopeType / scopeId / scopeVillageId}）と本文フィールドを同梱して送る
 * （{@code frontend/app/composables/bulletin/useBulletinThreads.ts createThread()}）。
 * VILLAGE スコープでは {@code scopeId=0} + {@code scopeVillageId=<村UUID>} を渡す。</p>
 *
 * <p>本 DTO は受信専用で、サービス層へは {@link #toCreateThreadRequest()} で
 * 既存 {@link CreateThreadRequest} に変換して委譲する。Jackson の bean バインディング
 * （JSON / multipart 双方）に対応するため {@code @NoArgsConstructor + @Setter} とする。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class GlobalCreateThreadRequest {

    /** スコープ種別（{@code VILLAGE / ORGANIZATION / TEAM / PERSONAL}）。 */
    @NotBlank
    private String scopeType;

    /** スコープ ID（VILLAGE 時は 0）。 */
    private Long scopeId;

    /** カテゴリ ID（任意・NULL = 未分類）。 */
    private Long categoryId;

    @NotBlank
    @Size(max = 200)
    private String title;

    @NotBlank
    private String body;

    @Size(max = 20)
    private String priority;

    @Size(max = 20)
    private String readTrackingMode;

    private String sourceType;

    private Long sourceId;

    /** 村スコープ ID（VILLAGE 時必須）。 */
    private UUID scopeVillageId;

    /** 投稿主体種別（VILLAGE で TEAM/ORGANIZATION 名義投稿時に指定。null = USER）。 */
    private VillageSubjectType postedAsSubjectType;

    /** 投稿主体 ID（{@link #postedAsSubjectType} が TEAM/ORGANIZATION の場合に必須）。 */
    private Long postedAsSubjectId;

    /**
     * サービス層が要求する {@link CreateThreadRequest} へ変換する。
     */
    public CreateThreadRequest toCreateThreadRequest() {
        return new CreateThreadRequest(categoryId, title, body, priority, readTrackingMode,
                sourceType, sourceId, scopeVillageId, postedAsSubjectType, postedAsSubjectId);
    }
}
