package com.mannschaft.app.bulletin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * スレッド優先度変更リクエスト DTO（F17.1 村掲示板グローバル方式）。
 *
 * <p>FE は {@code PATCH /api/v1/bulletin/threads/{threadId}/priority} に body {@code { "priority": "..." }}
 * を送る（{@code useBulletinThreads.ts changePriority()}）。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class ChangePriorityRequest {

    @NotBlank
    @Size(max = 20)
    private String priority;
}
