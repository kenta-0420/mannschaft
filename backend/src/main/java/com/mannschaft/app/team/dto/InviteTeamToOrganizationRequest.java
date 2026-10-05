package com.mannschaft.app.team.dto;

import java.util.UUID;

/**
 * 組織からチームへの加盟招待リクエスト（F01.2.1 §6.5）。
 *
 * <p>入力の検証（チーム slug の有無・添え書きの長さ）は、認可（組織 ADMIN であること）より<b>後</b>に Service が行う
 * （認可の順序: 認証 → スコープの存在 → 権限 → 入力検証 → 状態。§10）。そのため Bean Validation のアノテーションは付けない。</p>
 *
 * @param teamSlug 招待するチームの slug（必須）。操作者から見えないチームは、存在しない slug と同じ 404
 * @param groupId  加盟後に所属させるチームグループ（任意。グループ機能 on の組織の生存グループに限る。承諾時にそのまま確定する）
 * @param message  添え書き（任意。500コードポイントまで。空文字は null に正規化する）
 */
public record InviteTeamToOrganizationRequest(String teamSlug, UUID groupId, String message) {
}
