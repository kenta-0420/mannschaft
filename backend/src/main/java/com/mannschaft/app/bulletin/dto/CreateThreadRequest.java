package com.mannschaft.app.bulletin.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

import java.util.UUID;

/**
 * スレッド作成リクエストDTO。
 *
 * <p>F17.1 Phase 3: 村スコープ（scopeType=VILLAGE）対応のため
 * {@link #scopeVillageId} および {@link #postedAsSubjectType} / {@link #postedAsSubjectId} を追加。
 * 既存呼び出し元との後方互換のため、これら新フィールドは null 可。</p>
 */
@Getter
public class CreateThreadRequest {

    /**
     * カテゴリID。
     *
     * <p>設計書 F05.1 §3 に従い任意（NULL = 未分類）。通常の UI 投稿でカテゴリ指定は任意であり、
     * 自動生成スレッド（SAFETY_CHECK / SURVEY 連携）は NULL で作成される。</p>
     */
    private final Long categoryId;

    @NotBlank
    @Size(max = 200)
    private final String title;

    @NotBlank
    private final String body;

    @Size(max = 20)
    private final String priority;

    @Size(max = 20)
    private final String readTrackingMode;

    private final String sourceType;

    private final Long sourceId;

    /**
     * 村スコープ ID（F17.1 Phase 3）。{@code scopeType=VILLAGE} の場合に必須。
     */
    private final UUID scopeVillageId;

    /**
     * 投稿主体種別（F17.1 Phase 3）。null の場合は USER（個人投稿）扱い。
     * VILLAGE スコープで TEAM/ORGANIZATION 名義投稿する場合に指定する。
     */
    private final VillageSubjectType postedAsSubjectType;

    /**
     * 投稿主体 ID（F17.1 Phase 3）。{@link #postedAsSubjectType} が TEAM/ORGANIZATION の場合に必須。
     */
    private final Long postedAsSubjectId;

    /**
     * 既存呼び出し元との後方互換のためのコンストラクタ（村スコープ対応前）。
     */
    public CreateThreadRequest(Long categoryId, String title, String body, String priority,
                               String readTrackingMode, String sourceType, Long sourceId) {
        this(categoryId, title, body, priority, readTrackingMode, sourceType, sourceId,
                null, null, null);
    }

    /**
     * F17.1 Phase 3: 村スコープと投稿主体を明示指定する完全コンストラクタ。
     *
     * <p>{@code @JsonCreator} を付与することで、複数コンストラクタ存在時に Jackson が
     * デシリアライズ用コンストラクタを一意に特定できるようにしている。
     * これがないと Jackson がデシリアライザを構築できず（no suitable creator）、
     * Spring が {@code HttpMessageConversionException} を投げる。同例外は
     * {@code GlobalExceptionHandler} に個別ハンドラが無いため、
     * {@code POST /api/v1/{scopeType}/{scopeId}/bulletin/threads} が
     * <b>body の内容によらず常に 500</b> になる（クライアント入力起因の 500）。</p>
     *
     * <p>同じ F17.1 Phase 3 で同型の変更を受けた {@code SendMessageRequest} は
     * 既に本対応済みであり、本 DTO だけが漏れていた。対処は同 DTO と同一の型に揃える。</p>
     */
    @JsonCreator
    public CreateThreadRequest(@JsonProperty("categoryId") Long categoryId,
                               @JsonProperty("title") String title,
                               @JsonProperty("body") String body,
                               @JsonProperty("priority") String priority,
                               @JsonProperty("readTrackingMode") String readTrackingMode,
                               @JsonProperty("sourceType") String sourceType,
                               @JsonProperty("sourceId") Long sourceId,
                               @JsonProperty("scopeVillageId") UUID scopeVillageId,
                               @JsonProperty("postedAsSubjectType") VillageSubjectType postedAsSubjectType,
                               @JsonProperty("postedAsSubjectId") Long postedAsSubjectId) {
        this.categoryId = categoryId;
        this.title = title;
        this.body = body;
        this.priority = priority;
        this.readTrackingMode = readTrackingMode;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.scopeVillageId = scopeVillageId;
        this.postedAsSubjectType = postedAsSubjectType;
        this.postedAsSubjectId = postedAsSubjectId;
    }
}
