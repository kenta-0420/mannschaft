package com.mannschaft.app.publicview.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.publicview.dto.PublicEventResponse;
import com.mannschaft.app.publicview.dto.PublicScopeRef;
import com.mannschaft.app.publicview.error.PublicViewErrorCode;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * F19.1 Phase 7 公開イベントクエリサービス。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.2 Phase 7</p>
 *
 * <p>チームの {@code public_events_enabled} フラグ、
 * または組織の {@code public_events_enabled} フラグが {@code true} の場合のみ
 * 未ログインユーザーへのイベント一覧を返す。
 * フラグが {@code false}、またはチーム / 組織が PRIVATE / archived / 削除済 / 不在の場合は
 * {@link PublicViewErrorCode#PUBLIC_001}（404 へ正規化）を返す（IDOR 対策）。</p>
 *
 * <p><strong>クロスドメイン注意</strong>: publicview ドメインから event ドメインの
 * Repository を直接参照しているため、CLAUDE.md アーキテクチャ原則 §5 に従いコメントを付与する。
 * TODO: publicview→event クロスドメイン。将来は EventPublishedEvent で分離予定。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class PublicEventQueryService {

    // TODO: クロスドメイン参照(publicview→team)。将来はイベント駆動で分離予定
    private final TeamRepository teamRepository;

    // TODO: クロスドメイン参照(publicview→organization)。将来はイベント駆動で分離予定
    private final OrganizationRepository organizationRepository;

    // TODO: クロスドメイン参照(publicview→event)。将来はイベント駆動で分離予定
    private final EventRepository eventRepository;

    // ────────────────────────────────────────────────────────────
    // チームスコープ
    // ────────────────────────────────────────────────────────────

    /**
     * チームの公開イベント一覧を取得する。
     *
     * <p>チームが PUBLIC かつ {@code public_events_enabled=true} の場合のみ返す。
     * そうでない場合は {@link PublicViewErrorCode#PUBLIC_001}（404）を投げる。</p>
     *
     * @param teamId   対象チーム ID
     * @param pageable ページネーション
     * @return 公開イベントのページ
     * @throws BusinessException チームが PUBLIC でないか public_events_enabled=false の場合
     */
    public Page<PublicEventResponse> getTeamEvents(Long teamId, Pageable pageable) {
        // チームが PUBLIC か確認（PRIVATE / 不在 は 404 で隠蔽）
        TeamEntity team = teamRepository.findPublicTeamById(teamId)
                .orElseThrow(() -> new BusinessException(PublicViewErrorCode.PUBLIC_001));

        // public_events_enabled フラグを確認（false / null の場合は 404 で隠蔽）
        if (!Boolean.TRUE.equals(team.getPublicEventsEnabled())) {
            throw new BusinessException(PublicViewErrorCode.PUBLIC_001);
        }

        PublicScopeRef scopeRef = PublicScopeRef.ofTeam(teamId, team.getName());
        return eventRepository.findPublicByTeamId(teamId, pageable)
                .map(event -> toResponse(event, scopeRef));
    }

    // ────────────────────────────────────────────────────────────
    // 組織スコープ
    // ────────────────────────────────────────────────────────────

    /**
     * 組織の公開イベント一覧を取得する。
     *
     * <p>組織が PUBLIC かつ {@code public_events_enabled=true} の場合のみ返す。
     * そうでない場合は {@link PublicViewErrorCode#PUBLIC_001}（404）を投げる。</p>
     *
     * @param orgId    対象組織 ID
     * @param pageable ページネーション
     * @return 公開イベントのページ
     * @throws BusinessException 組織が PUBLIC でないか public_events_enabled=false の場合
     */
    public Page<PublicEventResponse> getOrganizationEvents(Long orgId, Pageable pageable) {
        // 組織が PUBLIC か確認（PRIVATE / 不在 は 404 で隠蔽）
        OrganizationEntity org = organizationRepository.findPublicOrganizationById(orgId)
                .orElseThrow(() -> new BusinessException(PublicViewErrorCode.PUBLIC_001));

        // public_events_enabled フラグを確認（false の場合は 404 で隠蔽）
        if (!org.isPublicEventsEnabled()) {
            throw new BusinessException(PublicViewErrorCode.PUBLIC_001);
        }

        PublicScopeRef scopeRef = PublicScopeRef.ofOrganization(orgId, org.getName());
        return eventRepository.findPublicByOrganizationId(orgId, pageable)
                .map(event -> toResponse(event, scopeRef));
    }

    // ────────────────────────────────────────────────────────────
    // 内部ヘルパ
    // ────────────────────────────────────────────────────────────

    /**
     * EventEntity を PublicEventResponse に変換する。
     *
     * <p>summary は 200 文字でトリミングする（パフォーマンス）。</p>
     */
    private PublicEventResponse toResponse(EventEntity event, PublicScopeRef scopeRef) {
        return new PublicEventResponse(
                event.getId(),
                event.getSlug(),
                event.getSubtitle(),
                truncate(event.getSummary(), 200),
                event.getStatus() != null ? event.getStatus().name() : null,
                event.getVenueName(),
                event.getVenueAddress(),
                event.getMaxCapacity(),
                event.getRegistrationCount(),
                scopeRef,
                toOffsetDateTime(event.getCreatedAt())
        );
    }

    /**
     * 文字列を指定の最大文字数でトリミングする。
     */
    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * LocalDateTime を OffsetDateTime に変換する（システムデフォルトタイムゾーン使用）。
     */
    private static OffsetDateTime toOffsetDateTime(LocalDateTime ldt) {
        if (ldt == null) {
            return null;
        }
        return ldt.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toOffsetDateTime();
    }
}
