package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.MembershipBanRequest;
import com.mannschaft.app.village.dto.ReportCreateRequest;
import com.mannschaft.app.village.dto.ReportResolveRequest;
import com.mannschaft.app.village.dto.ReportResponse;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.VillageReportEntity;
import com.mannschaft.app.village.entity.enums.VillageReportStatus;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageReportRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * F17.1 Phase 1 B7 — 村内通報 + モデレーション Service。
 *
 * <p>担当 API（出陣指示書 §4.11 / 3 EP）:</p>
 * <ul>
 *   <li>{@code POST   /api/v1/villages/{vid}/reports} — 通報送信（村人全員）</li>
 *   <li>{@code GET    /api/v1/villages/{vid}/reports} — 一覧取得（HEADMAN / ELDER のみ）</li>
 *   <li>{@code POST   /api/v1/villages/{vid}/reports/{id}/resolve} — 解決（HEADMAN / ELDER のみ）</li>
 * </ul>
 *
 * <p>セキュリティ要件:</p>
 * <ul>
 *   <li>レートリミット: 1 ユーザー 10 件/時（設計書 §6.4）</li>
 *   <li>通報者非開示: {@code reporter_user_id} は API レスポンスに含めず、
 *       {@code reporterDisplayName="ANONYMOUS_VILLAGER"} 固定で返す（§6.2）</li>
 *   <li>モデレーション権限: HEADMAN / ELDER のみ（§5）</li>
 * </ul>
 *
 * <p>アーキテクチャ原則:</p>
 * <ul>
 *   <li>原則1: {@code reporter_user_id} に FK を張らない（B1 既対応）</li>
 *   <li>原則5: {@code @Transactional} は village ドメイン内に閉じる。
 *       BAN 実行も村ドメイン内 {@link VillageMembershipService} 経由で行う</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageReportService {

    /** 通報レートリミット: 1 ユーザー / 1 時間あたりの最大通報件数（設計書 §6.4）。 */
    static final int REPORT_RATE_LIMIT_PER_HOUR = 10;

    /** レートリミット計測ウィンドウ（直近 1 時間）。 */
    static final java.time.Duration REPORT_RATE_WINDOW = java.time.Duration.ofHours(1);

    private final VillageRepository villageRepository;
    private final VillageReportRepository reportRepository;
    private final VillageMembershipRepository membershipRepository;
    /** BAN 実行のため B3 Service に委譲する（村ドメイン内）。 */
    private final VillageMembershipService membershipService;

    // ========================================================================
    // 4.11.1 通報送信
    // ========================================================================

    /**
     * 村内通報を作成する。村人（VILLAGER / ELDER / HEADMAN）であれば誰でも実行可。
     *
     * <ul>
     *   <li>レートリミット: 直近 1 時間に 10 件まで（{@link VillageErrorCode#VILLAGE_REPORT_RATE_LIMITED}）</li>
     *   <li>{@code reporter_user_id} は記録するが、API レスポンスには返さない</li>
     * </ul>
     */
    @Transactional
    public ReportResponse createReport(UUID villageId, Long reporterUserId, ReportCreateRequest request) {
        loadActiveVillage(villageId);

        // 村人判定（VISITOR / 未参加は通報不可・IDOR 対策）
        if (!isUserMember(villageId, reporterUserId)) {
            throw new BusinessException(VillageErrorCode.NOT_MEMBER);
        }

        // targetRefId は @NotBlank で担保されるが、@Valid を経由しないテスト呼び出しに備え追加防御
        if (request.targetRefId() == null || request.targetRefId().isBlank()) {
            throw new BusinessException(VillageErrorCode.VILLAGE_REPORT_INVALID_TARGET);
        }

        // レートリミット（10 件/時）
        LocalDateTime windowStart = LocalDateTime.now().minus(REPORT_RATE_WINDOW);
        long recent = reportRepository.countByReporterUserIdAndCreatedAtAfter(reporterUserId, windowStart);
        if (recent >= REPORT_RATE_LIMIT_PER_HOUR) {
            throw new BusinessException(VillageErrorCode.VILLAGE_REPORT_RATE_LIMITED);
        }

        VillageReportEntity saved = reportRepository.save(
                VillageReportEntity.builder()
                        .villageId(villageId)
                        .reporterUserId(reporterUserId)
                        .targetType(request.targetType())
                        .targetRefId(request.targetRefId())
                        .reasonCode(request.reasonCode())
                        .detail(request.detail())
                        .status(VillageReportStatus.PENDING)
                        .build()
        );
        log.info("Village report filed: villageId={} reportId={} targetType={} reasonCode={}",
                villageId, saved.getId(), request.targetType(), request.reasonCode());
        return ReportResponse.from(saved);
    }

    // ========================================================================
    // 4.11.2 通報一覧（HEADMAN / ELDER のみ）
    // ========================================================================

    /**
     * 村内通報の一覧を取得する。{@code status} が指定されなければ全件。
     * 実行者は HEADMAN または ELDER であること。
     *
     * <p>レスポンスは必ず {@code reporterDisplayName="ANONYMOUS_VILLAGER"} で
     * 通報者をマスクして返す。</p>
     */
    @Transactional(readOnly = true)
    public List<ReportResponse> listReports(UUID villageId,
                                            Long actorUserId,
                                            VillageReportStatus statusFilter,
                                            int page,
                                            int size) {
        loadActiveVillage(villageId);
        requireModerator(villageId, actorUserId);

        Pageable pageable = PageRequest.of(page, size);
        Page<VillageReportEntity> p = (statusFilter == null)
                ? reportRepository.findByVillageIdOrderByCreatedAtDesc(villageId, pageable)
                : reportRepository.findByVillageIdAndStatus(villageId, statusFilter, pageable);

        return p.getContent().stream().map(ReportResponse::from).toList();
    }

    // ========================================================================
    // 4.11.3 通報解決
    // ========================================================================

    /**
     * 通報を解決する。HEADMAN / ELDER のみ実行可。
     *
     * <ul>
     *   <li>状態遷移: PENDING / REVIEWING → RESOLVED または DISMISSED</li>
     *   <li>既に解決済み（RESOLVED / DISMISSED）の通報には {@link VillageErrorCode#VILLAGE_REPORT_ALREADY_RESOLVED}</li>
     *   <li>{@code actionTaken=BANNED} の場合、対象が MEMBERSHIP であれば {@link VillageMembershipService#ban} を呼ぶ</li>
     * </ul>
     *
     * <p>{@code @Transactional} は village ドメイン内に閉じる（原則5）。
     * {@code membershipService.ban} は同一ドメインの呼び出しなので OK。</p>
     */
    @Transactional
    public ReportResponse resolveReport(UUID villageId,
                                        UUID reportId,
                                        Long actorUserId,
                                        ReportResolveRequest request) {
        loadActiveVillage(villageId);
        VillageMembershipEntity actor = requireModerator(villageId, actorUserId);

        VillageReportEntity report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.VILLAGE_REPORT_NOT_FOUND));

        // 村跨ぎ IDOR 防止: パスの villageId と通報の villageId が一致しなければ 404 扱い
        if (!report.getVillageId().equals(villageId)) {
            throw new BusinessException(VillageErrorCode.VILLAGE_REPORT_NOT_FOUND);
        }

        if (report.getStatus() == VillageReportStatus.RESOLVED
                || report.getStatus() == VillageReportStatus.DISMISSED) {
            throw new BusinessException(VillageErrorCode.VILLAGE_REPORT_ALREADY_RESOLVED);
        }

        VillageReportStatus newStatus = request.resolution();
        if (newStatus != VillageReportStatus.RESOLVED && newStatus != VillageReportStatus.DISMISSED) {
            // PENDING / REVIEWING への遷移は解決 API としては不正
            throw new BusinessException(VillageErrorCode.VILLAGE_REPORT_INVALID_TARGET);
        }

        // BAN 連携（actionTaken=BANNED かつ対象が MEMBERSHIP の場合のみ）
        if (request.actionTaken() == ReportActionTaken.BANNED) {
            applyBanAction(report, actorUserId, request.note());
        }

        report.setStatus(newStatus);
        report.setHandlerMembershipId(actor.getId());
        report.setHandlerAction(request.actionTaken().name());
        report.setHandledAt(LocalDateTime.now());
        VillageReportEntity saved = reportRepository.save(report);
        log.info("Village report resolved: villageId={} reportId={} resolution={} actionTaken={} actorMembership={}",
                villageId, reportId, newStatus, request.actionTaken(), actor.getId());
        return ReportResponse.from(saved);
    }

    /**
     * 通報を起点に BAN を実行する。対象が MEMBERSHIP であれば {@code target_ref_id} を
     * メンバーシップ ID として解釈し {@link VillageMembershipService#ban} を呼ぶ。
     * それ以外の対象種別では BAN 不可とし {@link VillageErrorCode#VILLAGE_REPORT_INVALID_TARGET} を返す。
     */
    private void applyBanAction(VillageReportEntity report, Long actorUserId, String reason) {
        if (report.getTargetType() != com.mannschaft.app.village.entity.enums.VillageReportTargetType.MEMBERSHIP) {
            throw new BusinessException(VillageErrorCode.VILLAGE_REPORT_INVALID_TARGET);
        }
        UUID membershipId;
        try {
            membershipId = UUID.fromString(report.getTargetRefId());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(VillageErrorCode.VILLAGE_REPORT_INVALID_TARGET);
        }
        membershipService.ban(report.getVillageId(), membershipId, actorUserId,
                new MembershipBanRequest(reason));
    }

    // ========================================================================
    // 共通ヘルパ
    // ========================================================================

    /** 有効な村を取得する（削除/凍結済みは VILLAGE_001 / VILLAGE_027 で扱う）。 */
    private VillageEntity loadActiveVillage(UUID villageId) {
        VillageEntity v = villageRepository.findById(villageId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND));
        if (v.getDeletedAt() != null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
        }
        if (v.getArchivedAt() != null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_ALREADY_ARCHIVED);
        }
        return v;
    }

    /**
     * 当該ユーザーが対象村の<strong>現役</strong>モデレーター（HEADMAN / ELDER）であることを要求する。
     * 一般村人・非村人・退村済み・BAN 済みは {@link VillageErrorCode#MODERATION_FORBIDDEN}（403）。
     *
     * <p>BAN / 退村の検査は {@code findActiveByVillageIdAndSubject} のクエリに委譲する（#2284 §12）。
     * 従来はここで手書きの {@code bannedAt != null} 分岐を持っていたが、同じ判定が村ドメイン全体に
     * コピーされ 5 実装で書き忘れられていた。述語をクエリ 1 箇所に寄せ、書き忘れの余地を無くす。</p>
     *
     * @return モデレーターのメンバーシップ Entity（{@code handler_membership_id} 記録用）
     */
    private VillageMembershipEntity requireModerator(UUID villageId, Long actorUserId) {
        VillageMembershipEntity m = membershipRepository
                .findActiveByVillageIdAndSubject(villageId, VillageSubjectType.USER, actorUserId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN));
        if (m.getRole() != VillageRole.HEADMAN && m.getRole() != VillageRole.ELDER) {
            throw new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN);
        }
        return m;
    }

    /** 当該ユーザーが対象村の現役 USER 主体メンバーであるか（BAN 中は false）。 */
    private boolean isUserMember(UUID villageId, Long userId) {
        return membershipRepository
                .findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                        villageId, VillageSubjectType.USER, userId)
                .filter(m -> m.getBannedAt() == null)
                .isPresent();
    }

    // ========================================================================
    // 通報処理アクション enum
    // ========================================================================

    /**
     * 通報解決時に実施したアクション種別（指示書 §4.11 拡張版）。
     *
     * <ul>
     *   <li>{@link #NONE} — 何もしなかった（不正な通報・対応不要と判断）</li>
     *   <li>{@link #WARNED} — 対象ユーザーへ警告した</li>
     *   <li>{@link #CONTENT_REMOVED} — 通報対象コンテンツを削除した</li>
     *   <li>{@link #BANNED} — 対象ユーザーを BAN した（{@link VillageMembershipService#ban} を呼ぶ）</li>
     *   <li>{@link #VILLAGE_ARCHIVED} — 村ごと凍結した（HEADMAN 操作・別途 B2 API 経由想定）</li>
     * </ul>
     */
    public enum ReportActionTaken {
        NONE,
        WARNED,
        CONTENT_REMOVED,
        BANNED,
        VILLAGE_ARCHIVED
    }
}
