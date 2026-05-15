package com.mannschaft.app.residencestatus.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.residencestatus.ResidenceStatusErrorCode;
import com.mannschaft.app.residencestatus.dto.MonitoringCommitteeVisitDto;
import com.mannschaft.app.residencestatus.entity.MonitoringCommitteeVisit;
import com.mannschaft.app.residencestatus.repository.MonitoringCommitteeVisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * F09.16 S3-C 見守り委員訪問記録サービス。
 *
 * <p>見守り委員（WATCHER）による訪問結果の記録・更新・閲覧を提供する。
 * {@code considerationMemoEncrypted} は {@link com.mannschaft.app.common.EncryptedStringConverter}
 * により透過的に AES-256-GCM 暗号化されるため、Service 層では平文で扱う。</p>
 *
 * <p>{@code @Transactional} は residencestatus ドメイン内に閉じている（CLAUDE.md 原則 5）。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonitoringCommitteeVisitService {

    private final MonitoringCommitteeVisitRepository visitRepo;
    private final AccessControlService accessControlService;

    /** 訪問記録の更新可能期間（作成から 24 時間以内） */
    private static final long UPDATE_WINDOW_HOURS = 24;

    // ─────────────────────────────────────────────
    // 訪問記録の作成
    // ─────────────────────────────────────────────

    /**
     * 訪問記録を作成する（ADMIN/DEPUTY_ADMIN のみ）。
     *
     * <p>TODO: 将来は委員会 WATCHER ロール検証を実装予定（F04.10 委員会メンバー連携）。
     * v1 では ADMIN/DEPUTY_ADMIN による代理入力で対応する。</p>
     *
     * @param organizationId       テナント ID
     * @param committeeId          委員会 ID（F04.10 弱参照）
     * @param residentRegistryId   居住者台帳 ID（F09.1 弱参照）
     * @param dwellingUnitId       居室 ID（F09.1 弱参照）
     * @param subjectUserId        訪問対象ユーザー ID（弱参照）
     * @param visitorUserId        訪問者ユーザー ID（弱参照）
     * @param visitedAt            訪問日時
     * @param contactResult        訪問結果（enum name 文字列）
     * @param considerationMemo    配慮事項メモ（平文・暗号化は Entity 層で透過処理）
     * @param nextVisitRecommendedAt 次回訪問推奨日
     * @param consentCovenantId    MONITORING_CONSENT 誓約 ID（F09.15 弱参照・nullable）
     * @param requestUserId        操作ユーザー ID
     * @return 作成された訪問記録 DTO
     */
    @Transactional
    public MonitoringCommitteeVisitDto createVisit(
            Long organizationId,
            Long committeeId,
            Long residentRegistryId,
            Long dwellingUnitId,
            Long subjectUserId,
            Long visitorUserId,
            LocalDateTime visitedAt,
            String contactResult,
            String considerationMemo,
            LocalDate nextVisitRecommendedAt,
            UUID consentCovenantId,
            Long requestUserId) {

        // 権限確認: ADMIN/DEPUTY_ADMIN のみ許可
        // TODO: 将来は委員会 WATCHER ロール（F04.10）の検証を実装予定
        accessControlService.checkAdminOrAbove(requestUserId, organizationId, "ORGANIZATION");

        MonitoringCommitteeVisit visit = MonitoringCommitteeVisit.builder()
                .organizationId(organizationId)
                .committeeId(committeeId)
                .residentRegistryId(residentRegistryId)
                .dwellingUnitId(dwellingUnitId)
                .subjectUserId(subjectUserId)
                .visitorUserId(visitorUserId)
                .visitedAt(visitedAt)
                .contactResult(contactResult)
                .considerationMemoEncrypted(considerationMemo)
                .nextVisitRecommendedAt(nextVisitRecommendedAt)
                .consentCovenantId(consentCovenantId)
                .build();

        MonitoringCommitteeVisit saved = visitRepo.save(visit);
        log.info("訪問記録作成: organizationId={}, committeeId={}, residentRegistryId={}, id={}",
                organizationId, committeeId, residentRegistryId, saved.getId());

        return toDto(saved);
    }

    // ─────────────────────────────────────────────
    // 訪問記録の更新
    // ─────────────────────────────────────────────

    /**
     * 訪問記録を更新する。
     *
     * <p>更新できるのは訪問者本人または ADMIN のみ。
     * また作成から {@link #UPDATE_WINDOW_HOURS} 時間以内のみ更新可能。</p>
     *
     * @param organizationId       テナント ID
     * @param visitId              訪問記録 ID
     * @param requestUserId        操作ユーザー ID
     * @param contactResult        更新後の訪問結果
     * @param considerationMemo    更新後の配慮事項メモ
     * @param nextVisitRecommendedAt 更新後の次回訪問推奨日
     * @return 更新後の訪問記録 DTO
     */
    @Transactional
    public MonitoringCommitteeVisitDto updateVisit(
            Long organizationId,
            UUID visitId,
            Long requestUserId,
            String contactResult,
            String considerationMemo,
            LocalDate nextVisitRecommendedAt) {

        MonitoringCommitteeVisit visit = visitRepo
                .findByIdAndOrganizationIdAndDeletedAtIsNull(visitId, organizationId)
                .orElseThrow(() -> new BusinessException(ResidenceStatusErrorCode.MONITORING_VISIT_NOT_FOUND));

        // 24h 以内の更新可否チェック
        LocalDateTime expiry = visit.getCreatedAt().plusHours(UPDATE_WINDOW_HOURS);
        if (LocalDateTime.now().isAfter(expiry)) {
            throw new BusinessException(ResidenceStatusErrorCode.MONITORING_VISIT_UPDATE_EXPIRED);
        }

        // 訪問者本人 or ADMIN のみ更新可
        boolean isAdmin = accessControlService.isAdminOrAbove(requestUserId, organizationId, "ORGANIZATION");
        boolean isVisitor = visit.getVisitorUserId().equals(requestUserId);
        if (!isAdmin && !isVisitor) {
            throw new BusinessException(ResidenceStatusErrorCode.SNAPSHOT_ACCESS_FORBIDDEN);
        }

        visit.setContactResult(contactResult);
        visit.setConsiderationMemoEncrypted(considerationMemo);
        visit.setNextVisitRecommendedAt(nextVisitRecommendedAt);

        MonitoringCommitteeVisit saved = visitRepo.save(visit);
        log.info("訪問記録更新: organizationId={}, visitId={}, requestUserId={}",
                organizationId, visitId, requestUserId);

        return toDto(saved);
    }

    // ─────────────────────────────────────────────
    // 訪問記録の取得
    // ─────────────────────────────────────────────

    /**
     * 委員会の訪問記録一覧を取得する（ADMIN/DEPUTY_ADMIN のみ、直近順）。
     *
     * @param organizationId テナント ID
     * @param committeeId    委員会 ID
     * @param requestUserId  操作ユーザー ID
     * @return 訪問記録 DTO 一覧
     */
    public List<MonitoringCommitteeVisitDto> getVisitsByCommittee(
            Long organizationId, Long committeeId, Long requestUserId) {

        if (!accessControlService.isAdminOrAbove(requestUserId, organizationId, "ORGANIZATION")) {
            throw new BusinessException(ResidenceStatusErrorCode.SNAPSHOT_ACCESS_FORBIDDEN);
        }

        return visitRepo
                .findByCommitteeIdAndDeletedAtIsNullOrderByVisitedAtDesc(committeeId)
                .stream()
                .filter(v -> v.getOrganizationId().equals(organizationId))
                .map(this::toDto)
                .toList();
    }

    /**
     * 居住者の訪問記録一覧を取得する（ADMIN/DEPUTY_ADMIN のみ、直近順）。
     *
     * @param organizationId     テナント ID
     * @param residentRegistryId 居住者台帳 ID
     * @param requestUserId      操作ユーザー ID
     * @return 訪問記録 DTO 一覧
     */
    public List<MonitoringCommitteeVisitDto> getVisitsByResident(
            Long organizationId, Long residentRegistryId, Long requestUserId) {

        if (!accessControlService.isAdminOrAbove(requestUserId, organizationId, "ORGANIZATION")) {
            throw new BusinessException(ResidenceStatusErrorCode.SNAPSHOT_ACCESS_FORBIDDEN);
        }

        return visitRepo
                .findByResidentRegistryIdAndDeletedAtIsNullOrderByVisitedAtDesc(residentRegistryId)
                .stream()
                .filter(v -> v.getOrganizationId().equals(organizationId))
                .map(this::toDto)
                .toList();
    }

    // ─────────────────────────────────────────────
    // WATCHER 自身の訪問履歴取得（S4-A）
    // ─────────────────────────────────────────────

    /**
     * WATCHER（訪問者）自身が記録した訪問履歴を取得する。
     *
     * <p>権限: ADMIN または本人（visitorUserId == requestUserId）のみ許可。
     * それ以外は {@link ResidenceStatusErrorCode#SNAPSHOT_ACCESS_FORBIDDEN} を送出する。</p>
     *
     * @param organizationId   テナント ID
     * @param visitorUserId    訪問者ユーザー ID
     * @param requestUserId    リクエストユーザー ID
     * @return 訪問履歴 DTO 一覧（直近順）
     * @throws BusinessException SNAPSHOT_ACCESS_FORBIDDEN: ADMIN でも本人でもない場合
     */
    public List<MonitoringCommitteeVisitDto> getVisitsByWatcher(
            Long organizationId,
            Long visitorUserId,
            Long requestUserId) {

        boolean isAdmin = accessControlService.isAdminOrAbove(requestUserId, organizationId, "ORGANIZATION");
        boolean isSelf  = requestUserId.equals(visitorUserId);
        if (!isAdmin && !isSelf) {
            throw new BusinessException(ResidenceStatusErrorCode.SNAPSHOT_ACCESS_FORBIDDEN);
        }

        return visitRepo
                .findByVisitorUserIdAndOrganizationIdAndDeletedAtIsNullOrderByVisitedAtDesc(
                        visitorUserId, organizationId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    // ─────────────────────────────────────────────
    // private ヘルパー
    // ─────────────────────────────────────────────

    /**
     * Entity → DTO 変換。
     */
    private MonitoringCommitteeVisitDto toDto(MonitoringCommitteeVisit e) {
        return MonitoringCommitteeVisitDto.builder()
                .id(e.getId())
                .organizationId(e.getOrganizationId())
                .committeeId(e.getCommitteeId())
                .residentRegistryId(e.getResidentRegistryId())
                .dwellingUnitId(e.getDwellingUnitId())
                .subjectUserId(e.getSubjectUserId())
                .visitorUserId(e.getVisitorUserId())
                .visitedAt(e.getVisitedAt())
                .contactResult(e.getContactResult())
                .considerationMemo(e.getConsiderationMemoEncrypted())
                .nextVisitRecommendedAt(e.getNextVisitRecommendedAt())
                .consentCovenantId(e.getConsentCovenantId())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
