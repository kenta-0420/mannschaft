package com.mannschaft.app.tournament;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F08.7 大会・リーグ管理のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum TournamentErrorCode implements ErrorCode {

    /** 大会が見つからない */
    TOURNAMENT_NOT_FOUND("TOUR_001", "大会が見つかりません", Severity.WARN),

    /** ディビジョンが見つからない */
    DIVISION_NOT_FOUND("TOUR_002", "ディビジョンが見つかりません", Severity.WARN),

    /** 試合が見つからない */
    MATCH_NOT_FOUND("TOUR_003", "試合が見つかりません", Severity.WARN),

    /** 大会ステータスが操作を許可しない */
    INVALID_TOURNAMENT_STATUS("TOUR_004", "大会のステータスではこの操作を実行できません", Severity.WARN),

    /** 対戦カード自動生成には2チーム以上必要 */
    INSUFFICIENT_PARTICIPANTS("TOUR_005", "対戦カード自動生成には2チーム以上必要です", Severity.WARN),

    /** 同一チームの重複登録 */
    DUPLICATE_PARTICIPANT("TOUR_006", "同一チームが既に登録されています", Severity.WARN),

    /** スコア入力値が不正 */
    INVALID_SCORE("TOUR_007", "スコア入力値が不正です", Severity.WARN),

    /** セット数が sets_to_win を超過 */
    SETS_EXCEEDED("TOUR_008", "セット数が上限を超えています", Severity.WARN),

    /** 全試合完了前に昇降格は実行不可 */
    MATCHES_NOT_COMPLETED("TOUR_009", "全試合が完了するまで昇降格は実行できません", Severity.WARN),

    /** 存在しない stat_key */
    INVALID_STAT_KEY("TOUR_010", "大会に定義されていない成績項目キーです", Severity.WARN),

    /** 既に昇降格が実行済み */
    PROMOTION_ALREADY_EXECUTED("TOUR_011", "このチームの昇降格は既に実行済みです", Severity.WARN),

    /** max_participants を超過 */
    MAX_PARTICIPANTS_EXCEEDED("TOUR_012", "ディビジョンの最大参加チーム数を超えています", Severity.WARN),

    /** テンプレートが見つからない */
    TEMPLATE_NOT_FOUND("TOUR_013", "テンプレートが見つかりません", Severity.WARN),

    /** プリセットが見つからない */
    PRESET_NOT_FOUND("TOUR_014", "プリセットが見つかりません", Severity.WARN),

    /** KNOCKOUT形式で引分けは不可 */
    KNOCKOUT_DRAW_NOT_ALLOWED("TOUR_015", "トーナメント形式では引分けは認められません", Severity.WARN),

    /** league_round_type が大会形式と不整合 */
    INVALID_LEAGUE_ROUND_TYPE("TOUR_016", "リーグラウンド設定が大会形式と整合しません", Severity.WARN),

    /** 参加チームが ACTIVE でない */
    PARTICIPANT_NOT_ACTIVE("TOUR_017", "参加チームのステータスがACTIVEでないため操作できません", Severity.WARN),

    /** 参加チームが見つからない */
    PARTICIPANT_NOT_FOUND("TOUR_018", "参加チームが見つかりません", Severity.WARN),

    /** エントリー表が見つからない */
    ENTRY_MEMBER_NOT_FOUND("TOUR_019", "エントリー表メンバーが見つかりません", Severity.WARN),

    /** エントリーが編集ロック中 */
    ENTRY_LOCKED("TOUR_020", "大会のステータスによりエントリーを編集できません", Severity.WARN),

    /** チームメンバーではないユーザーを指定した */
    USER_NOT_TEAM_MEMBER("TOUR_021", "指定されたユーザーはチームメンバーではありません", Severity.WARN),

    /** 最小エントリー数を満たしていない */
    MIN_ENTRY_COUNT_VIOLATION("TOUR_022", "最小エントリー人数を満たしていません", Severity.WARN),

    /** 最大エントリー数を超過している */
    MAX_ENTRY_COUNT_EXCEEDED("TOUR_023", "最大エントリー人数を超えています", Severity.WARN),

    /** エントリーテンプレートが見つからない */
    ENTRY_TEMPLATE_NOT_FOUND("TOUR_024", "エントリーテンプレートが見つかりません", Severity.WARN),

    /** テンプレート登録上限（5件）超過 */
    MAX_TEMPLATE_COUNT_EXCEEDED("TOUR_025", "エントリーテンプレートは最大5件まで登録できます", Severity.WARN),

    /** チームが組織に所属していない */
    TEAM_NOT_IN_ORGANIZATION("TOUR_026", "チームがこの組織に所属していません", Severity.WARN),

    /** 同一ユーザーが既にエントリー済み */
    DUPLICATE_ENTRY_MEMBER("TOUR_027", "このユーザーは既にエントリー済みです", Severity.WARN),

    /** テンプレートとチームが一致しない */
    TEMPLATE_TEAM_MISMATCH("TOUR_028", "テンプレートのチームと参加チームが一致しません", Severity.WARN),

    /** 連絡スペースが見つからない（F08.7.1・IDOR 対策で 404 に統一） */
    CONTACT_SPACE_NOT_FOUND("TOUR_029", "連絡スペースが見つかりません", Severity.WARN),

    /** 連絡スペースの閲覧権限がない（F08.7.1 §4.1） */
    CONTACT_SPACE_VIEW_FORBIDDEN("TOUR_030", "この連絡スペースを閲覧する権限がありません", Severity.WARN),

    /** 連絡スペースへの投稿権限がない（F08.7.1 §4.2） */
    CONTACT_SPACE_POST_FORBIDDEN("TOUR_031", "この連絡スペースへ投稿する権限がありません", Severity.WARN),

    /** 連絡スペースの公開設定を変更する権限がない（F08.7.1 §5・主催組織 ADMIN 限定） */
    CONTACT_SPACE_VISIBILITY_FORBIDDEN("TOUR_032", "連絡スペースの公開設定を変更する権限がありません", Severity.WARN),

    /** 大会参加費（tournament_fee）が見つからない（F08.7.1/07・IDOR 対策で 404 に統一） */
    FEE_NOT_FOUND("TOUR_033", "大会参加費が見つかりません", Severity.WARN),

    /** 大会参加費の作成・更新・削除の権限がない（F08.7.1/07 §6・主催組織 ADMIN 限定） */
    FEE_MANAGE_FORBIDDEN("TOUR_034", "大会参加費を管理する権限がありません", Severity.WARN),

    /** 大会参加費の支払い権限がない（F08.7.1/07 §6・自チーム ADMIN/DEPUTY_ADMIN 限定） */
    FEE_PAY_FORBIDDEN("TOUR_035", "この参加費を支払う権限がありません", Severity.WARN),

    /** payment_item が主催組織に属していない（F08.7.1/07 §3.1・スコープ不一致） */
    FEE_PAYMENT_ITEM_SCOPE_MISMATCH("TOUR_036", "指定された支払い項目が主催組織に属していません", Severity.WARN),

    /** 支払おうとしたチームが参加費の対象（SPECIFIC_TEAMS）に含まれていない（F08.7.1/07 §2） */
    FEE_TEAM_NOT_TARGET("TOUR_037", "このチームは参加費の対象に含まれていません", Severity.WARN),

    // ========================================================================
    // F08.7.1 / 03 リーグ・ピラミッド＋昇降格移籍（league_transfer）
    // ========================================================================

    /** リーグ移籍記録が見つからない（IDOR 対策で 404 に統一・§7） */
    LEAGUE_TRANSFER_NOT_FOUND("TOUR_038", "リーグ移籍記録が見つかりません", Severity.WARN),

    /** 昇降格送り出しの権限がない（手放す側 org ADMIN 限定・§7） */
    LEAGUE_TRANSFER_DISPATCH_FORBIDDEN("TOUR_039", "昇降格送り出しの権限がありません", Severity.WARN),

    /** 昇降格の承認・拒否・取消の権限がない（受け入れ/手放す側 org ADMIN 限定・§7） */
    LEAGUE_TRANSFER_RESPOND_FORBIDDEN("TOUR_040", "この移籍に応答する権限がありません", Severity.WARN),

    /** チーム側の移籍閲覧権限がない（当該チーム MEMBER 以上限定・§7） */
    LEAGUE_TRANSFER_VIEW_FORBIDDEN("TOUR_041", "このチームの移籍状況を閲覧する権限がありません", Severity.WARN),

    /** 移籍の送り先 org が解決できない（祖先/子孫 ASSOCIATION が 0 件・§5.2/§5.3・症状を握りつぶさず例外化） */
    LEAGUE_TRANSFER_TARGET_NOT_RESOLVABLE("TOUR_042",
            "移籍の送り先組織を解決できません（親子関係が確認できません）", Severity.WARN),

    /** 指定チームが境界部の昇格枠/降格枠に該当しない（§3.3・独自境界判定） */
    LEAGUE_TRANSFER_TEAM_NOT_IN_SLOT("TOUR_043",
            "指定されたチームは昇格枠/降格枠に含まれていません", Severity.WARN),

    /** 既に同一チーム・同一シーズン・同一方向の移籍が起票済み（UNIQUE 制約・二重起票防止・§7） */
    LEAGUE_TRANSFER_ALREADY_DISPATCHED("TOUR_044",
            "このチームの当該シーズンの移籍は既に起票済みです", Severity.WARN),

    /** 移籍が応答可能な状態（DISPATCHED）でない（状態機械違反・§3.2） */
    LEAGUE_TRANSFER_NOT_DISPATCHED("TOUR_045",
            "この移籍は既に応答済み、または取消済みです", Severity.WARN),

    // ========================================================================
    // F08.7.1 / 05 試合メンバー表（roster）
    // 採番衝突回避: league_transfer（隊3）が TOUR_038-045 を先取りしたため末尾 046-050 に再配置
    // ========================================================================

    /** 呼び出しユーザーの所属チームが当該試合の対戦当事者でない（F08.7.1/05 §4・rosters/me） */
    ROSTER_TEAM_NOT_IN_MATCH("TOUR_046", "あなたのチームはこの試合の対戦当事者ではありません", Severity.WARN),

    /** 自チームメンバー表の編集/提出権限がない（F08.7.1/05 §5・自チーム ADMIN/DEPUTY 限定） */
    ROSTER_EDIT_FORBIDDEN("TOUR_047", "このメンバー表を編集する権限がありません", Severity.WARN),

    /** 提出締切（roster_deadline）超過のため編集ロック中（F08.7.1/05 §5・409） */
    ROSTER_DEADLINE_PASSED("TOUR_048", "メンバー表の提出締切を過ぎているため編集できません", Severity.WARN),

    /** 主催組織 ADMIN でないため全チームのメンバー表閲覧/締切管理ができない（F08.7.1/05 §5） */
    ROSTER_MANAGE_FORBIDDEN("TOUR_049", "メンバー表を管理する権限がありません", Severity.WARN),

    /** 指定ユニフォームセットが自チームのものでない / 存在しない（F08.7.1/05 §8.2・8.5） */
    UNIFORM_SET_NOT_FOUND("TOUR_050", "指定されたユニフォームセットが見つかりません", Severity.WARN),

    // ========================================================================
    // F08.7.1 / 06 書類提出受付（submission）
    // 採番衝突回避: league_transfer（隊3）が 038-045、roster（隊5）が 046-050 を先取りしたため末尾 051-058 に配置
    // ========================================================================

    /** 大会提出枠（tournament_submission_requirement）が見つからない（F08.7.1/06・IDOR 対策で 404 に統一） */
    SUBMISSION_REQ_NOT_FOUND("TOUR_051", "提出枠が見つかりません", Severity.WARN),

    /** 提出枠の作成・更新・削除・状況閲覧の権限がない（F08.7.1/06 §7・主催組織 ADMIN 限定） */
    SUBMISSION_REQ_MANAGE_FORBIDDEN("TOUR_052", "提出枠を管理する権限がありません", Severity.WARN),

    /** 提出枠の閲覧権限がない（F08.7.1/06 §7・自チーム ADMIN/DEPUTY または主催組織 ADMIN） */
    SUBMISSION_REQ_VIEW_FORBIDDEN("TOUR_053", "この提出枠を閲覧する権限がありません", Severity.WARN),

    /** 自チーム分の提出権限がない（F08.7.1/06 §7・自チーム ADMIN/DEPUTY_ADMIN 限定） */
    SUBMISSION_SUBMIT_FORBIDDEN("TOUR_054", "この提出枠へ提出する権限がありません", Severity.WARN),

    /** 提出しようとしたチームが提出枠の対象（SPECIFIC_TEAMS）に含まれていない（F08.7.1/06 §2） */
    SUBMISSION_TEAM_NOT_TARGET("TOUR_055", "このチームは提出枠の対象に含まれていません", Severity.WARN),

    /** 提出締切を過ぎている（F08.7.1/06 §4・5） */
    SUBMISSION_DEADLINE_PASSED("TOUR_056", "提出締切を過ぎています", Severity.WARN),

    /** 大会参加費が未払いのため提出できない／受理できない（F08.7.1/06 §5・requires_payment ゲート） */
    SUBMISSION_PAYMENT_REQUIRED("TOUR_057", "大会参加費の支払いが完了していません", Severity.WARN),

    /** 提出枠に指定された form_template が主催組織に属していない（F08.7.1/06 §3・スコープ不一致） */
    SUBMISSION_TEMPLATE_SCOPE_MISMATCH("TOUR_058", "指定された書類テンプレートが主催組織に属していません", Severity.WARN),

    // ========================================================================
    // F08.7 順位UI 項目③ スコアキーパー指名（scorekeeper）
    // ========================================================================

    /** スコアキーパー指名の管理（一覧/追加/削除）権限がない（主催組織 ADMIN 限定） */
    SCOREKEEPER_MANAGE_FORBIDDEN("TOUR_059", "スコアキーパーを管理する権限がありません", Severity.WARN),

    /** スコアキーパー指名が見つからない（IDOR 対策で 404 に統一） */
    SCOREKEEPER_NOT_FOUND("TOUR_060", "スコアキーパー指名が見つかりません", Severity.WARN),

    // ========================================================================
    // 認可根治戦役 Wave 2 トランシェ2C（tournament）
    // path で渡された ID をそのまま信用せず、親エンティティ（大会/ディビジョン）との
    // 束縛を検証して不一致は 404 で存在秘匿する（BOLA 是正）
    // ========================================================================

    /** 節（matchday）が指定ディビジョン配下に見つからない（IDOR 対策で 404 に統一） */
    MATCHDAY_NOT_FOUND("TOUR_061", "節が見つかりません", Severity.WARN),

    /** 出場メンバー（fixture roster）が指定大会配下に見つからない（IDOR 対策で 404 に統一） */
    FIXTURE_ROSTER_NOT_FOUND("TOUR_062", "出場メンバーが見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
