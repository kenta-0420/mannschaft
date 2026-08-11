package com.mannschaft.app.membership.fanout;

import com.mannschaft.app.notification.fanout.FanoutRecipientSource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * キープ変換通知（F03.17 §6.1）の TEAM スコープ受信者ソース（CMP-017c）。
 *
 * <p>母集団 = TEAM スコープの <b>MEMBER 以上（{@code role_kind='MEMBER'}）全員</b>から
 * <b>操作者・作成者を除いた</b>集合。純 SUPPORTER（{@code role_kind='SUPPORTER'}）と
 * メンバーシップを持たない GUEST は母集団段階で除外し、可視性再チェックをしない
 * 一括配信（{@link com.mannschaft.app.notification.service.NotificationBulkFanoutService}）でも
 * タイトルを漏らさない（§6.1・CMP-017b の SUPPORTER 素通り欠陥を母集団側で塞ぐ）。</p>
 *
 * <h2>本クラスは red 時点のスケルトン</h2>
 * <p>試練（red）では {@code nextPage} が空を返す骨格のみ。母集団の keyset クエリ結線は出陣（green）で実装する。</p>
 */
@Component
public class ScheduleKeepTeamFanoutRecipientSource implements FanoutRecipientSource {

    /** レジストリ解決キー（ジョブ表 {@code scope_type} と一致）。 */
    public static final String SCOPE_TYPE = "SCHEDULE_KEEP_TEAM";

    @Override
    public String scopeType() {
        return SCOPE_TYPE;
    }

    @Override
    public List<Long> nextPage(String scopeRef, long cursorSubjectId, int limit) {
        // 出陣で実装（scope_ref="teamId:actorId:creatorId" を復元し MEMBER 以上を keyset 供給）。
        return List.of();
    }
}
