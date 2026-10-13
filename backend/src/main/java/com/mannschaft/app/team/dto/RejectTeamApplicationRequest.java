package com.mannschaft.app.team.dto;

/**
 * 組織による加盟申請の拒否リクエスト（F01.2.1 §10.5）。
 *
 * <p>入力の検証（理由の長さ）は認可より<b>後</b>に Service が行うため、Bean Validation は付けない。</p>
 *
 * @param reason チームへ通知する理由（任意。500コードポイントまで。空文字・空白だけは null に正規化する）
 * @param block  true なら無期限ブロック（任意。既定 false）
 */
public record RejectTeamApplicationRequest(String reason, Boolean block) {
}
