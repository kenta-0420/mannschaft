package com.mannschaft.app.team.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.team.dto.TeamOrgAffiliationResponse.AffiliationPartyRef;
import com.mannschaft.app.team.dto.TeamOrgAffiliationRestrictionResponse;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgAffiliationDirection;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionEntity;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionKind;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionReason;
import com.mannschaft.app.team.repository.TeamOrgAffiliationRestrictionRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 止めた側による、加盟の再送制限の一覧と解除（F01.2.1 §5.4「ブロック」「解除一覧に出す行」・§10.1）。
 *
 * <h2>止めた側だけが、自分で作った制限だけを解除できる（迂回の封止）</h2>
 * <ul>
 *   <li>組織側: 組織が拒否で止めた<b>申請</b>（{@code TEAM_APPLY}・{@code REJECTED}）。チーム自身の取下げで止まった申請
 *       （{@code WITHDRAWN}）や、チームが止めた招待（{@code ORG_INVITE}）は、一覧に出さず解除もさせない。</li>
 *   <li>チーム側: チームが辞退で止めた<b>招待</b>（{@code ORG_INVITE}・{@code DECLINED}）。組織自身の取消で止まった招待
 *       （{@code CANCELLED}）や、組織が止めた申請（{@code TEAM_APPLY}）は、一覧に出さず解除もさせない。</li>
 * </ul>
 * <p>解除は ID・自スコープ・向き・理由がすべて一致する行だけを消す条件付き DELETE で行う。他組織・他チームの ID、
 * 相手が作った行、存在しない ID は区別せず同じ 404 を返す（存在オラクルを作らない。AC-C12・G137）。
 * 制限 ID 専用のエラーコードは設計書 §11 に無いため、汎用の {@code COMMON_005}（404）を使う。</p>
 *
 * <p>認可（組織側は組織 ADMIN、チーム側はチームの加盟操作者）は呼び出し元の Controller が先に行う。
 * 一覧は名前解決（チーム・組織）がドメインをまたぐため、トランザクションを持たない。</p>
 */
@Service
public class TeamOrgAffiliationRestrictionAdminService {

    /** 一覧の1ページの既定件数と上限（§10）。 */
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final TeamOrgAffiliationRestrictionRepository repository;
    private final TeamRepository teamRepository;
    private final TeamAffiliationOrganizationPort organizationPort;
    private final Clock clock;

    public TeamOrgAffiliationRestrictionAdminService(TeamOrgAffiliationRestrictionRepository repository,
                                                     TeamRepository teamRepository,
                                                     TeamAffiliationOrganizationPort organizationPort,
                                                     Clock clock) {
        this.repository = repository;
        this.teamRepository = teamRepository;
        this.organizationPort = organizationPort;
        this.clock = clock;
    }

    /**
     * 組織が止めている申請（TEAM_APPLY・REJECTED）のうち、いま有効なものを作成日時の降順で返す。
     */
    public PagedResponse<TeamOrgAffiliationRestrictionResponse> listByOrganization(Long organizationId,
                                                                                  int page, int size) {
        Page<TeamOrgAffiliationRestrictionEntity> result = repository.findActivePageByOrganization(
                organizationId, TeamOrgAffiliationDirection.TEAM_APPLY, TeamOrgAffiliationRestrictionReason.REJECTED,
                TeamOrgAffiliationRestrictionKind.BLOCK, Instant.now(clock), pageRequest(page, size));
        return toPaged(result);
    }

    /**
     * チームが止めている招待（ORG_INVITE・DECLINED）のうち、いま有効なものを作成日時の降順で返す。
     */
    public PagedResponse<TeamOrgAffiliationRestrictionResponse> listByTeam(Long teamId, int page, int size) {
        Page<TeamOrgAffiliationRestrictionEntity> result = repository.findActivePageByTeam(
                teamId, TeamOrgAffiliationDirection.ORG_INVITE, TeamOrgAffiliationRestrictionReason.DECLINED,
                TeamOrgAffiliationRestrictionKind.BLOCK, Instant.now(clock), pageRequest(page, size));
        return toPaged(result);
    }

    /**
     * 組織が止めた申請の制限を解除する。他組織の ID・相手が作った行・存在しない ID は同じ 404（AC-C12）。
     */
    @Transactional
    public void liftByOrganization(Long organizationId, UUID restrictionId) {
        int deleted = repository.deleteOwnedByOrganization(restrictionId, organizationId,
                TeamOrgAffiliationDirection.TEAM_APPLY, TeamOrgAffiliationRestrictionReason.REJECTED);
        if (deleted == 0) {
            throw new BusinessException(CommonErrorCode.COMMON_005);
        }
    }

    /**
     * チームが止めた招待の制限を解除する。他チームの ID・相手が作った行・存在しない ID は同じ 404（AC-G137）。
     */
    @Transactional
    public void liftByTeam(Long teamId, UUID restrictionId) {
        int deleted = repository.deleteOwnedByTeam(restrictionId, teamId,
                TeamOrgAffiliationDirection.ORG_INVITE, TeamOrgAffiliationRestrictionReason.DECLINED);
        if (deleted == 0) {
            throw new BusinessException(CommonErrorCode.COMMON_005);
        }
    }

    private PagedResponse<TeamOrgAffiliationRestrictionResponse> toPaged(
            Page<TeamOrgAffiliationRestrictionEntity> result) {
        List<TeamOrgAffiliationRestrictionEntity> rows = result.getContent();
        Set<Long> teamIds = new HashSet<>();
        Set<Long> organizationIds = new HashSet<>();
        for (TeamOrgAffiliationRestrictionEntity row : rows) {
            teamIds.add(row.getTeamId());
            organizationIds.add(row.getOrganizationId());
        }
        // 名前はまとめて引く（チーム1本・組織1本。行数に比例して SQL を増やさない）
        Map<Long, AffiliationPartyRef> teams = new HashMap<>();
        if (!teamIds.isEmpty()) {
            for (TeamEntity team : teamRepository.findAllById(teamIds)) {
                teams.put(team.getId(), new AffiliationPartyRef(team.getSlug(), team.getName(), team.getIconUrl()));
            }
        }
        Map<Long, TeamAffiliationOrganizationPort.OrganizationRef> organizations =
                organizationPort.findOrganizationRefs(organizationIds);

        List<TeamOrgAffiliationRestrictionResponse> data = rows.stream()
                .map(row -> {
                    TeamAffiliationOrganizationPort.OrganizationRef org = organizations.get(row.getOrganizationId());
                    return new TeamOrgAffiliationRestrictionResponse(
                            row.getId(),
                            row.getDirection().name(),
                            row.getKind().name(),
                            row.getReason().name(),
                            toOffset(row.getRestrictedUntil()),
                            teams.getOrDefault(row.getTeamId(), new AffiliationPartyRef(null, null, null)),
                            org == null
                                    ? new AffiliationPartyRef(null, null, null)
                                    : new AffiliationPartyRef(org.slug(), org.name(), org.iconUrl()),
                            toOffset(row.getCreatedAt()));
                })
                .toList();
        return PagedResponse.of(data, new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages()));
    }

    private static PageRequest pageRequest(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize);
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : instant.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toOffsetDateTime();
    }
}
