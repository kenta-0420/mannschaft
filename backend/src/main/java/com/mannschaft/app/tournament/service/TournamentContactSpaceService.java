package com.mannschaft.app.tournament.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.dto.ContactSpaceResponse;
import com.mannschaft.app.tournament.entity.TournamentContactSpaceEntity;
import com.mannschaft.app.tournament.repository.TournamentContactSpaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 連絡スペースの照会・公開トグル管理サービス（F08.7.1 §5）。
 *
 * <p>公開トグルは主催組織 ADMIN / SYSTEM_ADMIN 限定（{@link TournamentContactAccessService#checkVisibilityManage}）。
 * 変更は {@code audit_logs} に {@link AuditEventType#TOURNAMENT_CONTACT_SPACE_VISIBILITY_UPDATED} として記録する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TournamentContactSpaceService {

    private final TournamentContactSpaceRepository contactSpaceRepository;
    private final TournamentContactAccessService tournamentContactAccessService;
    private final AuditLogService auditLogService;

    /**
     * スコープに紐づく連絡スペース一覧を返す（閲覧者は閲覧認可を通す）。
     */
    public List<ContactSpaceResponse> listSpaces(ContactSpaceScopeType scopeType, Long scopeId, Long userId) {
        // 一覧は少なくとも 1 スペースの閲覧権限があれば見せる方針ではなく、スコープ単位の認可とする。
        // 公開管理画面は主催者が叩くため、ここでは管理認可（checkVisibilityManage）を通す。
        tournamentContactAccessService.checkVisibilityManage(scopeType, scopeId, userId);
        return contactSpaceRepository.findByScopeTypeAndScopeId(scopeType, scopeId).stream()
                .map(ContactSpaceResponse::from)
                .toList();
    }

    /**
     * 連絡スペースの公開フラグを切り替える（§5.1）。主催組織 ADMIN / SYSTEM_ADMIN 限定。
     *
     * @param scopeType スコープ種別（TOURNAMENT / TOURNAMENT_DIVISION）
     * @param scopeId   大会 ID / ディビジョン ID（パス整合・IDOR 検証用）
     * @param spaceId   スペース ID（UUIDv7）
     * @param isPublic  公開する場合 true
     * @param userId    操作ユーザー ID
     * @return 更新後のスペース
     * @throws BusinessException 認可なし（403）／スペースが当該スコープに存在しない（404）
     */
    @Transactional
    public ContactSpaceResponse updateVisibility(ContactSpaceScopeType scopeType, Long scopeId,
                                                 UUID spaceId, boolean isPublic, Long userId) {
        // 主催組織 ADMIN / SYSTEM_ADMIN のみ（チーム代表は不可・§5）。スコープ存在性もここで 404 になる。
        tournamentContactAccessService.checkVisibilityManage(scopeType, scopeId, userId);

        // スペース存在性 + スコープ一致（他スコープのスペース ID を渡す IDOR を 404 で弾く）
        TournamentContactSpaceEntity space = contactSpaceRepository.findById(spaceId)
                .filter(s -> s.getScopeType() == scopeType && s.getScopeId().equals(scopeId))
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.CONTACT_SPACE_NOT_FOUND));

        space.changeVisibility(isPublic);
        TournamentContactSpaceEntity saved = contactSpaceRepository.save(space);

        // 監査ログ（誰が・いつ・どのスペースを公開/非公開にしたか・§5.2）
        String metadata = String.format(
                "{\"source\":\"TOURNAMENT_CONTACT_SPACE\",\"space_id\":\"%s\",\"scope_type\":\"%s\","
                        + "\"scope_id\":%d,\"space_kind\":\"%s\",\"is_public\":%b}",
                spaceId, scopeType.name(), scopeId, saved.getSpaceKind().name(), isPublic);
        auditLogService.record(
                AuditEventType.TOURNAMENT_CONTACT_SPACE_VISIBILITY_UPDATED.name(),
                userId, null, null, null, null, null, null, metadata);

        log.info("連絡スペース公開設定変更: spaceId={}, scope={}/{}, isPublic={}, by={}",
                spaceId, scopeType, scopeId, isPublic, userId);
        return ContactSpaceResponse.from(saved);
    }
}
