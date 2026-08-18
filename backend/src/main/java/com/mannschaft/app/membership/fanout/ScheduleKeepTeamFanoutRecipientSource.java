package com.mannschaft.app.membership.fanout;

import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.notification.fanout.FanoutRecipientSource;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * キープ変換通知（F03.17 §6.1・CMP-017c）の TEAM スコープ受信者ソース。
 *
 * <p>母集団 = TEAM スコープの <b>MEMBER 以上（{@code role_kind='MEMBER'}）全員</b>から
 * <b>操作者・作成者を除いた</b>集合。純 SUPPORTER（{@code role_kind='SUPPORTER'}）と
 * メンバーシップを持たない GUEST は母集団段階で除外し、可視性再チェックをしない一括配信
 * （{@link com.mannschaft.app.notification.service.NotificationBulkFanoutService}）でもタイトルを漏らさない
 * （§6.1・CMP-017b）。</p>
 *
 * <h2>配置ドメイン（越境 Repository 依存の解消・D-5 番人）</h2>
 * <p>受信者解決は {@link MembershipRepository}（membership ドメイン）を引くため、本実装は membership ドメイン
 * （{@code com.mannschaft.app.membership.fanout}）に置く。共有契約 {@link FanoutRecipientSource} は
 * notification/fanout に残し membership 側が実装する（依存性逆転・{@link TeamFanoutRecipientSource} と同型）。</p>
 *
 * <h2>scope_ref のフォーマット {@code "teamId:actorId:creatorId"}</h2>
 * <p>ジョブ表の多型スコープ参照 {@code scope_ref} に対象チーム ID・変換操作者・キープ作成者の 3 値を
 * コロン区切りで格納する。操作者は「自分の操作を自分に通知しない」ため、作成者は「必達の直送で受領し
 * 二重送信を避ける」ため、いずれも母集団から除く。作成者が匿名化済み（{@code created_by IS NULL}）の場合は
 * 3 番目が {@code 0}（除外対象なし・user_id は常に正の番人値）。</p>
 */
@Component
@RequiredArgsConstructor
public class ScheduleKeepTeamFanoutRecipientSource implements FanoutRecipientSource {

    /** レジストリ解決キー（ジョブ表 {@code scope_type} と一致）。 */
    public static final String SCOPE_TYPE = "SCHEDULE_KEEP_TEAM";

    /** 除外枠に渡す番人値（user_id は常に正のため実 user_id と衝突しない）。 */
    private static final long NO_EXCLUSION = 0L;

    private final MembershipRepository membershipRepository;

    @Override
    public String scopeType() {
        return SCOPE_TYPE;
    }

    @Override
    public List<Long> nextPage(String scopeRef, long cursorSubjectId, int limit) {
        // scope_ref = "teamId:actorId:creatorId" を復元する（多型スコープ参照）。
        String[] parts = scopeRef.split(":", -1);
        long teamId = Long.parseLong(parts[0]);
        long actorId = parts.length > 1 && !parts[1].isEmpty() ? Long.parseLong(parts[1]) : NO_EXCLUSION;
        long creatorId = parts.length > 2 && !parts[2].isEmpty() ? Long.parseLong(parts[2]) : NO_EXCLUSION;
        // MEMBER 以上（role_kind='MEMBER'）の現役・ACTIVE・未削除メンバーから操作者・作成者を除き、
        // 被覆索引で index-only の keyset ページングにより 1 チャンクぶんの user_id を昇順に返す。
        return membershipRepository.findMemberAndAboveTeamUserIdsByKeysetExcluding(
                teamId, actorId, creatorId, cursorSubjectId, PageRequest.of(0, limit));
    }
}
