package com.mannschaft.app.bulletin.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * スレッドのロック状態設定リクエスト DTO（set 方式・F17.1 村掲示板グローバル方式）。
 *
 * <p>FE は {@code PATCH /api/v1/bulletin/threads/{threadId}/lock} に body {@code { "locked": true|false }}
 * を送る（{@code useBulletinThreads.ts toggleLock()}）。トグルではなく明示値で設定する。
 * 未指定（null）は後方互換で false（解除）として扱う。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class SetLockRequest {

    /** 設定するロック状態。null は false（解除）として扱う。 */
    private Boolean locked;

    /** ロック状態を取得する（null セーフ）。 */
    public boolean resolveLocked() {
        return Boolean.TRUE.equals(locked);
    }
}
