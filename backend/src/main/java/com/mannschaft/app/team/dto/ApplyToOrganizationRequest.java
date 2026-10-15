package com.mannschaft.app.team.dto;

import java.util.UUID;

/**
 * チームから組織への加盟申請リクエスト（F01.2.1 §10.4）。
 *
 * <p>入力の検証（組織 slug の形式・添え書きの長さ）は、認可と可視性の確認より<b>後</b>に Service が行う
 * （認可の順序: 認証 → スコープの存在と可視性 → 権限 → 入力検証 → 状態。§10）。
 * そのため Bean Validation のアノテーションは付けない。</p>
 *
 * @param organizationSlug 申請先の組織の slug（必須。3〜30文字）
 * @param groupId          希望するチームグループの ID（任意。組織の設定により必須・不可・任意が決まる。§6.1 step 10）
 * @param message          添え書き（任意。500コードポイントまで。空文字は null に正規化する）
 */
public record ApplyToOrganizationRequest(String organizationSlug, UUID groupId, String message) {
}
