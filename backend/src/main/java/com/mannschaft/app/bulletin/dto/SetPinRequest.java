package com.mannschaft.app.bulletin.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * スレッドのピン留め状態設定リクエスト DTO（set 方式・F17.1 村掲示板グローバル方式）。
 *
 * <p>FE は {@code PATCH /api/v1/bulletin/threads/{threadId}/pin} に body {@code { "pinned": true|false }}
 * を送る（{@code useBulletinThreads.ts togglePin()}）。トグルではなく明示値で設定する。
 * 未指定（null）は後方互換で false（解除）として扱う。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class SetPinRequest {

    /** 設定するピン留め状態。null は false（解除）として扱う。 */
    private Boolean pinned;

    /** ピン留め状態を取得する（null セーフ）。 */
    public boolean resolvePinned() {
        return Boolean.TRUE.equals(pinned);
    }
}
