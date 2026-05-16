package com.mannschaft.app.bulletin.dto;

import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    @NotNull
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
     */
    public CreateThreadRequest(Long categoryId, String title, String body, String priority,
                               String readTrackingMode, String sourceType, Long sourceId,
                               UUID scopeVillageId,
                               VillageSubjectType postedAsSubjectType,
                               Long postedAsSubjectId) {
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
