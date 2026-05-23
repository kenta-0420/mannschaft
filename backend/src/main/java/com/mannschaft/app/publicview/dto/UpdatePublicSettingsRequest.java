package com.mannschaft.app.publicview.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * F19.1 Phase 7: チーム / 組織の公開設定（タイムライン投稿 / イベント）PATCH リクエスト DTO。
 *
 * <p>ADMIN または SYSTEM_ADMIN が操作可能（権限チェックは Controller の
 * {@code @PreAuthorize} または Service 層で実施）。</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class UpdatePublicSettingsRequest {

    /**
     * タイムライン投稿を公開するか。
     * true=未ログインユーザーにもタイムライン投稿を表示する / false=非表示にする。
     */
    @NotNull(message = "timelinePostsPublic は必須です")
    private Boolean timelinePostsPublic;

    /**
     * 公開イベントを有効にするか。
     * true=未ログインユーザーにもイベントを表示する / false=非表示にする。
     */
    @NotNull(message = "publicEventsEnabled は必須です")
    private Boolean publicEventsEnabled;
}
