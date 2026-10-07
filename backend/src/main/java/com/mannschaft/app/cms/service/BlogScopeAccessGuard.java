package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsErrorCode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ブログのスコープ（チーム／組織）の門（CMP-261007-2052）。
 *
 * <p>一覧・詳細の双方で、記事を読む<b>前</b>に「スコープの識別子（slug・数値文字列）をチームはチーム・
 * 組織は組織として解決し、実在・ACTIVE で、閲覧者がスコープ自体を見られること」を判定する。
 * 不存在・論理削除済み・PROVISIONED・閲覧不可は、slug・数値の別なくすべて呼び出し側が指定する同一の
 * 「不存在」エラーに畳む（存在秘匿）。</p>
 *
 * <p><b>なぜ {@code @Transactional} を付けず、Controller から先に呼ぶのか</b>: {@link BlogPostService} は
 * クラス単位で {@code @Transactional} のため、そこから他ドメイン（team / organization / F00 Resolver 経由の
 * auth）の Repository へ推移的に到達すると、取引が cms ドメインの外へ越境する（D-3T 番人、CLAUDE.md DB 設計の
 * 原則 #5）。本部品は取引を持たず、他ドメインの Service と F00 Checker をそれぞれの取引で呼ぶ。
 * 判定に失敗した例外もそれぞれの取引の中で完結するため、外側に rollback-only の取引が残らない。
 * {@link BlogPostService} には解決済みの ID だけを渡す。</p>
 */
@Component
@RequiredArgsConstructor
public class BlogScopeAccessGuard {

    private final TeamService teamService;
    private final OrganizationService organizationService;
    private final ContentVisibilityChecker contentVisibilityChecker;

    /** 詳細用に解決したスコープ。チーム・組織のどちらか一方、または両方 null（個人記事）。 */
    public record ResolvedScope(Long teamId, Long organizationId) {
    }

    /**
     * 一覧用: チームの識別子を解決し、閲覧者が見られることを確認する。
     *
     * @return 内部チームID
     * @throws BusinessException 不存在・削除済み・PROVISIONED・閲覧不可（{@link CmsErrorCode#TEAM_NOT_FOUND}、404）
     */
    public Long resolveVisibleTeam(String teamIdStr, Long viewerUserId) {
        return resolveVisible(teamIdStr, true, viewerUserId, CmsErrorCode.TEAM_NOT_FOUND);
    }

    /**
     * 一覧用: 組織の識別子を解決し、閲覧者が見られることを確認する。
     *
     * @return 内部組織ID
     * @throws BusinessException 不存在・削除済み・PROVISIONED・閲覧不可（{@link CmsErrorCode#ORG_NOT_FOUND}、404）
     */
    public Long resolveVisibleOrganization(String organizationIdStr, Long viewerUserId) {
        return resolveVisible(organizationIdStr, false, viewerUserId, CmsErrorCode.ORG_NOT_FOUND);
    }

    /**
     * 詳細用: チーム（優先）または組織の識別子を解決し、閲覧者が見られることを確認する。
     * どちらも未指定（null/空白）なら個人記事として両方 null を返す。
     *
     * @throws BusinessException 不存在・削除済み・PROVISIONED・閲覧不可（{@link CmsErrorCode#POST_NOT_FOUND}、404）
     */
    public ResolvedScope resolveVisibleScopeForDetail(String teamIdStr, String organizationIdStr, Long viewerUserId) {
        if (!isBlank(teamIdStr)) {
            return new ResolvedScope(resolveVisible(teamIdStr, true, viewerUserId, CmsErrorCode.POST_NOT_FOUND), null);
        }
        if (!isBlank(organizationIdStr)) {
            return new ResolvedScope(null,
                    resolveVisible(organizationIdStr, false, viewerUserId, CmsErrorCode.POST_NOT_FOUND));
        }
        return new ResolvedScope(null, null);
    }

    /**
     * 詳細用: 個人記事経路の {@code userId} 文字列を解釈する。null・空白は未指定（null）、数値はその ID。
     * 数値でない値は記事不存在と同一の {@link CmsErrorCode#POST_NOT_FOUND}（404）。
     *
     * <p>{@code Long} で受けるとグローバルの slug 変換器が空白・非数値をチームの slug として解決しようとして
     * 別のエラーコードの 404 になり、「スコープ未指定は CMS_001」（AC-20）とそろわないため、文字列で受けてここで解く。</p>
     */
    public Long parsePersonalUserId(String userIdStr) {
        if (isBlank(userIdStr)) {
            return null;
        }
        Long id = parseLongOrNull(userIdStr.strip());
        if (id == null) {
            throw new BusinessException(CmsErrorCode.POST_NOT_FOUND);
        }
        return id;
    }

    private Long resolveVisible(String idStr, boolean team, Long viewerUserId, CmsErrorCode notFound) {
        if (isBlank(idStr)) {
            throw new BusinessException(notFound);
        }
        String value = idStr.strip();
        try {
            Long id = parseLongOrNull(value);
            if (team) {
                if (id == null) {
                    // slug 解決は ACTIVE・未削除のチームに限る（TeamService 側の契約）
                    id = teamService.resolveTeamId(value);
                }
                teamService.assertActiveTeamExists(id);
                contentVisibilityChecker.assertCanView(ReferenceType.TEAM, id, viewerUserId);
            } else {
                if (id == null) {
                    id = organizationService.resolveOrgId(value);
                }
                organizationService.assertActiveOrganizationExists(id);
                contentVisibilityChecker.assertCanView(ReferenceType.ORGANIZATION, id, viewerUserId);
            }
            return id;
        } catch (BusinessException e) {
            // 不存在・削除済み・PROVISIONED・閲覧不可の区別を応答に出さない（存在秘匿）。
            throw new BusinessException(notFound);
        }
    }

    private static Long parseLongOrNull(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
