package com.mannschaft.app.bulletin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * グローバル方式 一括既読リクエスト DTO（F17.1 村掲示板グローバル方式）。
 *
 * <p>FE は {@code POST /api/v1/bulletin/threads/read-all} の body にスコープ情報を同梱して送る
 * （{@code frontend/app/composables/bulletin/useBulletinThreads.ts readAll()}）。
 * VILLAGE スコープでは {@code scopeId=0} + {@code scopeVillageId=<村UUID>} を渡す。</p>
 *
 * <p>Jackson の bean バインディングに対応するため {@code @NoArgsConstructor + @Setter} とする。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class ReadAllRequest {

    /** スコープ種別（{@code VILLAGE / ORGANIZATION / TEAM / PERSONAL}）。 */
    @NotBlank
    private String scopeType;

    /** スコープ ID（VILLAGE 時は 0）。 */
    private Long scopeId;

    /** 村スコープ ID（VILLAGE 時必須）。 */
    private UUID scopeVillageId;
}
