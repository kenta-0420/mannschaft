package com.mannschaft.app.repairplan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * シナリオを告知として公開するリクエスト（F08.8 Phase 2）。
 *
 * <p>楽観ロック用の {@code version} を必須とし、ロック済みシナリオへの重複操作を検知する。</p>
 */
public record PublishAsAnnouncementRequest(
        @NotBlank @Size(max = 200) String announcementTitle,
        @Size(max = 100) String proposedResolutionNo,
        @NotNull Long version
) {}
