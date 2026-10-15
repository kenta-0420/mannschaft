package com.mannschaft.app.event.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 点呼セッションレスポンスDTO。F03.12 §14 主催者点呼機能。
 *
 * <p>POST /roll-call の結果サマリを返す。
 * guardianSetupWarnings に含まれるユーザーはケア対象者として登録されているが
 * 見守り者が設定されておらず、チェックイン通知が誰にも届かないことを示す。</p>
 */
@Getter
@Builder
public class RollCallSessionResponse {

    /** 処理した点呼セッションID（リクエストの rollCallSessionId をそのまま返す）。 */
    private final String rollCallSessionId;

    /** 新規作成したチェックインレコード数。 */
    private final int createdCount;

    /** 既存レコードを上書き更新した数（冪等再送の場合に増加）。 */
    private final int updatedCount;

    /** 保護者通知を送信した件数。 */
    private final int guardianNotificationsSent;

    /**
     * ケア対象者だが見守り者が未設定のユーザー名リスト。
     * 空リストの場合は警告なし。
     */
    private final List<String> guardianSetupWarnings;
}
