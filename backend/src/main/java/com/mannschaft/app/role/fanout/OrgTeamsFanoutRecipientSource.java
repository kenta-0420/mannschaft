package com.mannschaft.app.role.fanout;

import com.mannschaft.app.notification.fanout.FanoutPageRequest;
import com.mannschaft.app.notification.fanout.FanoutRecipient;
import com.mannschaft.app.notification.fanout.FanoutRecipientRowMapper;
import com.mannschaft.app.notification.fanout.FanoutRecipientSource;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * F01.2.1 §8.5.2: 宛先を絞ったアンケート告知の push 用の受信者ソース（{@code ORGANIZATION_TEAMS}）。
 *
 * <p>{@code scope_ref} は宛先集合のキー {@code audience_snapshot_id}（UUID 文字列。§5.6）。組織 ID は宛先集合の見出し
 * （{@code notification_fanout_audiences.organization_id}）から引く。母集団は
 * 「宛先チーム（{@code notification_fanout_audience_teams}）のうち<b>配信時点でも</b>組織に ACTIVE で加盟し、
 * アーカイブ済み・論理削除済みでないものの現役メンバー」∪「組織の直属メンバー」を、ユーザー単位で重複排除したもの。宛先チームは送信時に固定され、
 * 受信ユーザーは Worker が各チャンクを取るたびにその時点の所属で決まる（§8.5.2・AC-H33）。
 * 子組織のメンバー・宛先外チームのメンバーは含めない。</p>
 *
 * <h2>異常系は例外（空集合で完了させない・AC-H32c）</h2>
 * <p>{@code scope_ref} が UUID として解釈できない、または見出し行が無いときは例外を投げる。空集合を返すと Worker が
 * 「受信者ゼロ」としてジョブを DONE にしてしまい、配信漏れが静かに起きるため。例外なら Worker がジョブを失敗として
 * 残し、{@code last_error} に出る。</p>
 *
 * <h2>配置ドメイン</h2>
 * <p>{@code OrgFanoutRecipientSource} と同じ理由で role ドメインに置く（受信者解決は {@link UserRoleRepository}
 * のネイティブクエリで行い、他ドメインの Repository に依存しない。D-5 番人）。</p>
 */
@Component
@RequiredArgsConstructor
public class OrgTeamsFanoutRecipientSource implements FanoutRecipientSource {

    /** レジストリ解決キー（{@code notification_fanout_jobs.scope_type} VARCHAR(20) に収まる）。 */
    public static final String SCOPE_TYPE = "ORGANIZATION_TEAMS";

    private final UserRoleRepository userRoleRepository;

    @Override
    public String scopeType() {
        return SCOPE_TYPE;
    }

    /**
     * 受信者ページを 1 チャンク供給する。{@code shardCount <= 1} は分割なし（全件）、{@code shardCount > 1} は
     * {@code MOD(user_id, shardCount) = shardIndex} の区画だけを返す。
     *
     * @throws IllegalArgumentException scope_ref が UUID でないとき
     * @throws IllegalStateException    宛先集合の見出し行が無いとき
     */
    @Override
    public List<FanoutRecipient> nextPage(FanoutPageRequest request) {
        String audienceId = parseAudienceId(request.scopeRef());
        long organizationId = requireOrganizationId(audienceId);
        int shardCount = request.isSingleShard() ? 1 : request.shardCount();
        int shardIndex = request.isSingleShard() ? 0 : request.shardIndex();
        return FanoutRecipientRowMapper.toRecipients(
                userRoleRepository.findDistributionUserIdsForOrgTeamsAudienceKeyset(
                        organizationId, audienceId, request.includeSupporters(),
                        request.cursorSubjectId(), request.limit(), shardIndex, shardCount));
    }

    /**
     * 受信者総数（自動シャード数の算出に使う）。母集団条件は配信の keyset クエリと同一の定義を共有する。
     *
     * @throws IllegalArgumentException scope_ref が UUID でないとき
     * @throws IllegalStateException    宛先集合の見出し行が無いとき
     */
    @Override
    public long countRecipients(String scopeRef, boolean includeSupporters) {
        String audienceId = parseAudienceId(scopeRef);
        long organizationId = requireOrganizationId(audienceId);
        return userRoleRepository.countDistributionUserIdsForOrgTeamsAudience(
                organizationId, audienceId, includeSupporters);
    }

    /** scope_ref を UUID として解釈し、ハイフン付きの正規形で返す（UUID でなければ {@link IllegalArgumentException}）。 */
    private static String parseAudienceId(String scopeRef) {
        if (scopeRef == null) {
            throw new IllegalArgumentException("ORGANIZATION_TEAMS の scope_ref が null です");
        }
        try {
            return UUID.fromString(scopeRef).toString();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "ORGANIZATION_TEAMS の scope_ref は宛先集合の UUID である必要があります: " + scopeRef, e);
        }
    }

    private long requireOrganizationId(String audienceId) {
        return userRoleRepository.findOrganizationIdOfFanoutAudience(audienceId)
                .orElseThrow(() -> new IllegalStateException(
                        "fan-out の宛先集合の見出しが見つかりません（空集合で完了させない）: audienceSnapshotId=" + audienceId));
    }
}
