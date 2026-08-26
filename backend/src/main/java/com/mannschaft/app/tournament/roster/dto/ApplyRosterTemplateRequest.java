package com.mannschaft.app.tournament.roster.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * エントリーテンプレを自チーム分メンバー表へ適用するリクエスト（F08.7.1/05 §4 apply-template）。
 *
 * <p>テンプレの選手（背番号・ポジション・協会登録番号）＋ベンチ役員を roster へ複製する。
 * {@code overwriteExisting=true} の場合は既存の自チーム roster を全置換する。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class ApplyRosterTemplateRequest {

    @NotNull
    private UUID templateId;

    /** 既存の自チーム roster を全置換するか（既定 false＝既存があれば何もしない） */
    private boolean overwriteExisting;

    /** テンプレ適用時に着用ユニフォームセットの既定値を設定する（NULL 可・§8.4） */
    private UUID defaultUniformSetId;
}
