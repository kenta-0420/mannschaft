package com.mannschaft.app.supporter.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.membership.domain.LeaveReason;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.dto.MembershipLeaveRequest;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.supporter.SupporterApplicationStatus;
import com.mannschaft.app.supporter.SupporterErrorCode;
import com.mannschaft.app.supporter.dto.BulkApproveRequest;
import com.mannschaft.app.supporter.dto.FollowStatusResponse;
import com.mannschaft.app.supporter.dto.SupporterApplicationResponse;
import com.mannschaft.app.supporter.dto.SupporterResponse;
import com.mannschaft.app.supporter.dto.SupporterSettingsResponse;
import com.mannschaft.app.supporter.dto.UpdateSupporterSettingsRequest;
import com.mannschaft.app.supporter.entity.SupporterApplicationEntity;
import com.mannschaft.app.supporter.entity.SupporterSettingsEntity;
import com.mannschaft.app.supporter.repository.SupporterApplicationRepository;
import com.mannschaft.app.supporter.repository.SupporterSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * サポーター申請・管理サービス。
 * チーム・組織共通のサポーター申請フロー（申請/承認/却下/設定管理）を担当する。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SupporterService {

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final SupporterApplicationRepository applicationRepository;
    private final SupporterSettingsRepository settingsRepository;
    private final MembershipService membershipService;
    private final MembershipRepository membershipRepository;
    private final UserRepository userRepository;

    // ========================================
    // フォロー申請
    // ========================================

    /**
     * サポーター申請を行う。
     * autoApprove=true なら即時承認（SUPPORTER ロール付与）、false なら PENDING 申請を作成する。
     *
     * @param userId    申請ユーザーID
     * @param scopeType TEAM または ORGANIZATION
     * @param scopeId   チームID または 組織ID
     * @return 申請後のフォロー状態
     */
    @Transactional
    public ApiResponse<FollowStatusResponse> follow(Long userId, String scopeType, Long scopeId) {
        ScopeType scope = ScopeType.valueOf(scopeType);

        // 既にアクティブなメンバーシップがあれば申請不可
        boolean alreadyMember = membershipRepository.existsActiveByUserAndScope(userId, scope, scopeId);
        if (alreadyMember) {
            throw new BusinessException(SupporterErrorCode.SUPPORTER_002);
        }

        boolean autoApprove = getSettings(scopeType, scopeId).autoApprove();

        if (autoApprove) {
            // 即時承認: SUPPORTER メンバーシップを付与
            MembershipCreateRequest req = new MembershipCreateRequest();
            req.setUserId(userId);
            req.setScopeType(scope);
            req.setScopeId(scopeId);
            req.setRoleKind(RoleKind.SUPPORTER);
            req.setSource("SELF_SUPPORTER_REGISTRATION");
            membershipService.join(req);
            log.info("サポーター即時承認: scopeType={}, scopeId={}, userId={}", scopeType, scopeId, userId);
            return ApiResponse.of(FollowStatusResponse.approved());
        }
        else {
            // 手動承認: PENDING 申請を作成
            boolean alreadyApplied = applicationRepository.existsByScopeTypeAndScopeIdAndUserIdAndStatus(
                    scopeType, scopeId, userId, SupporterApplicationStatus.PENDING);
            if (alreadyApplied) {
                throw new BusinessException(SupporterErrorCode.SUPPORTER_001);
            }

            applicationRepository.save(SupporterApplicationEntity.builder()
                    .scopeType(scopeType)
                    .scopeId(scopeId)
                    .userId(userId)
                    .status(SupporterApplicationStatus.PENDING)
                    .build());
            log.info("サポーター申請作成: scopeType={}, scopeId={}, userId={}", scopeType, scopeId, userId);
            return ApiResponse.of(FollowStatusResponse.pending());
        }
    }

    /**
     * サポーター登録・申請を取り消す。
     * APPROVED（SUPPORTER ロール保持）または PENDING（申請中）どちらの状態でも取消可能。
     *
     * @param userId    ユーザーID
     * @param scopeType TEAM または ORGANIZATION
     * @param scopeId   チームID または 組織ID
     */
    @Transactional
    public void unfollow(Long userId, String scopeType, Long scopeId) {
        ScopeType scope = ScopeType.valueOf(scopeType);

        // APPROVED: SUPPORTER メンバーシップを退会
        Optional<MembershipEntity> activeMembership = membershipRepository.findActiveByUserAndScope(
                userId, scope, scopeId);
        if (activeMembership.isPresent()) {
            MembershipLeaveRequest leaveReq = new MembershipLeaveRequest();
            leaveReq.setLeaveReason(LeaveReason.SELF);
            membershipService.leave(activeMembership.get().getId(), leaveReq);
        }

        // PENDING: 申請レコードを削除
        applicationRepository.findByScopeTypeAndScopeIdAndUserIdAndStatus(
                        scopeType, scopeId, userId, SupporterApplicationStatus.PENDING)
                .ifPresent(applicationRepository::delete);

        log.info("サポーター解除: scopeType={}, scopeId={}, userId={}", scopeType, scopeId, userId);
    }

    /**
     * ユーザーのフォロー状態を取得する。
     *
     * @param userId    ユーザーID
     * @param scopeType TEAM または ORGANIZATION
     * @param scopeId   チームID または 組織ID
     * @return NONE / PENDING / APPROVED
     */
    public ApiResponse<FollowStatusResponse> getFollowStatus(Long userId, String scopeType, Long scopeId) {
        ScopeType scope = ScopeType.valueOf(scopeType);

        // SUPPORTER メンバーシップ保持 → APPROVED
        boolean isApproved = membershipRepository.existsActiveByUserAndScopeAndRoleKind(
                userId, scope, scopeId, RoleKind.SUPPORTER);
        if (isApproved) {
            return ApiResponse.of(FollowStatusResponse.approved());
        }

        // PENDING 申請あり → PENDING
        boolean isPending = applicationRepository.existsByScopeTypeAndScopeIdAndUserIdAndStatus(
                scopeType, scopeId, userId, SupporterApplicationStatus.PENDING);
        if (isPending) {
            return ApiResponse.of(FollowStatusResponse.pending());
        }

        return ApiResponse.of(FollowStatusResponse.none());
    }

    // ========================================
    // 管理者向け: サポーター一覧
    // ========================================

    /**
     * 承認済みサポーター一覧を取得する（ページネーション）。
     *
     * @param scopeType TEAM または ORGANIZATION
     * @param scopeId   チームID または 組織ID
     * @param pageable  ページングパラメータ
     */
    public PagedResponse<SupporterResponse> getSupporters(String scopeType, Long scopeId, Pageable pageable) {
        ScopeType scope = ScopeType.valueOf(scopeType);

        Page<MembershipEntity> page = membershipRepository.findByScopeAndActiveAndRoleKind(
                scope, scopeId, RoleKind.SUPPORTER, pageable);

        List<SupporterResponse> data = page.getContent().stream()
                .map(m -> {
                    UserEntity user = userRepository.findById(m.getUserId()).orElse(null);
                    String displayName = user != null ? user.getDisplayName() : "不明";
                    String avatarUrl = user != null ? user.getAvatarUrl() : null;
                    String followedAt = m.getJoinedAt() != null
                            ? m.getJoinedAt().format(ISO_FORMATTER)
                            : null;
                    return new SupporterResponse(m.getUserId(), displayName, avatarUrl, followedAt);
                })
                .toList();

        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
        return PagedResponse.of(data, meta);
    }

    // ========================================
    // 管理者向け: 申請一覧
    // ========================================

    /**
     * サポーター申請一覧を取得する（全ステータス、ページネーション）。
     * フロントエンドは PENDING のみ表示するが、バックエンドは全ステータスを返す。
     *
     * @param scopeType TEAM または ORGANIZATION
     * @param scopeId   チームID または 組織ID
     * @param pageable  ページングパラメータ
     */
    public PagedResponse<SupporterApplicationResponse> getApplications(
            String scopeType, Long scopeId, Pageable pageable) {

        Page<SupporterApplicationEntity> page = applicationRepository
                .findByScopeTypeAndScopeIdOrderByCreatedAtDesc(scopeType, scopeId, pageable);

        List<SupporterApplicationResponse> data = page.getContent().stream()
                .map(app -> {
                    UserEntity user = userRepository.findById(app.getUserId()).orElse(null);
                    String displayName = user != null ? user.getDisplayName() : "不明";
                    String avatarUrl = user != null ? user.getAvatarUrl() : null;
                    return new SupporterApplicationResponse(
                            app.getId(),
                            app.getUserId(),
                            displayName,
                            avatarUrl,
                            app.getMessage(),
                            app.getStatus().name(),
                            app.getCreatedAt().format(ISO_FORMATTER));
                })
                .toList();

        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
        return PagedResponse.of(data, meta);
    }

    // ========================================
    // 管理者向け: 個別・一括承認/却下
    // ========================================

    /**
     * サポーター申請を個別承認する。
     * 申請ステータスを APPROVED に変更し、SUPPORTER ロールを付与する。
     *
     * @param applicationId 申請ID
     * @param scopeType     TEAM または ORGANIZATION（申請の帰属確認用）
     * @param scopeId       チームID または 組織ID
     */
    @Transactional
    public void approve(Long applicationId, String scopeType, Long scopeId) {
        SupporterApplicationEntity app = findPendingApplicationOrThrow(applicationId, scopeType, scopeId);
        app.updateStatus(SupporterApplicationStatus.APPROVED);

        MembershipCreateRequest req = new MembershipCreateRequest();
        req.setUserId(app.getUserId());
        req.setScopeType(ScopeType.valueOf(scopeType));
        req.setScopeId(scopeId);
        req.setRoleKind(RoleKind.SUPPORTER);
        req.setSource("SUPPORTER_APPLICATION");
        membershipService.join(req);
        log.info("サポーター承認: applicationId={}, userId={}", applicationId, app.getUserId());
    }

    /**
     * サポーター申請を個別却下する。
     *
     * @param applicationId 申請ID
     * @param scopeType     TEAM または ORGANIZATION（申請の帰属確認用）
     * @param scopeId       チームID または 組織ID
     */
    @Transactional
    public void reject(Long applicationId, String scopeType, Long scopeId) {
        SupporterApplicationEntity app = findPendingApplicationOrThrow(applicationId, scopeType, scopeId);
        app.updateStatus(SupporterApplicationStatus.REJECTED);
        log.info("サポーター却下: applicationId={}, userId={}", applicationId, app.getUserId());
    }

    /**
     * サポーター申請を一括承認する。
     *
     * @param request   applicationIds を含むリクエスト
     * @param scopeType TEAM または ORGANIZATION
     * @param scopeId   チームID または 組織ID
     */
    @Transactional
    public void bulkApprove(BulkApproveRequest request, String scopeType, Long scopeId) {
        ScopeType scope = ScopeType.valueOf(scopeType);
        for (Long appId : request.getApplicationIds()) {
            SupporterApplicationEntity app = findPendingApplicationOrThrow(appId, scopeType, scopeId);
            app.updateStatus(SupporterApplicationStatus.APPROVED);

            MembershipCreateRequest req = new MembershipCreateRequest();
            req.setUserId(app.getUserId());
            req.setScopeType(scope);
            req.setScopeId(scopeId);
            req.setRoleKind(RoleKind.SUPPORTER);
            req.setSource("SUPPORTER_APPLICATION");
            membershipService.join(req);
        }
        log.info("サポーター一括承認: scopeType={}, scopeId={}, count={}",
                scopeType, scopeId, request.getApplicationIds().size());
    }

    // ========================================
    // 管理者向け: 設定
    // ========================================

    /**
     * サポーター設定を取得する。設定レコードが存在しない場合は autoApprove=true を返す。
     *
     * @param scopeType TEAM または ORGANIZATION
     * @param scopeId   チームID または 組織ID
     */
    public SupporterSettingsResponse getSettings(String scopeType, Long scopeId) {
        return settingsRepository.findByScopeTypeAndScopeId(scopeType, scopeId)
                .map(s -> new SupporterSettingsResponse(s.isAutoApprove()))
                .orElse(new SupporterSettingsResponse(true)); // デフォルト: 自動承認ON
    }

    /**
     * サポーター設定を更新する。設定レコードが存在しない場合は新規作成する。
     *
     * @param scopeType TEAM または ORGANIZATION
     * @param scopeId   チームID または 組織ID
     * @param request   更新内容
     */
    @Transactional
    public SupporterSettingsResponse updateSettings(
            String scopeType, Long scopeId, UpdateSupporterSettingsRequest request) {

        SupporterSettingsEntity settings = settingsRepository
                .findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElse(SupporterSettingsEntity.builder()
                        .scopeType(scopeType)
                        .scopeId(scopeId)
                        .autoApprove(request.isAutoApprove())
                        .build());

        settings.updateAutoApprove(request.isAutoApprove());
        settingsRepository.save(settings);
        log.info("サポーター設定更新: scopeType={}, scopeId={}, autoApprove={}",
                scopeType, scopeId, request.isAutoApprove());
        return new SupporterSettingsResponse(settings.isAutoApprove());
    }

    // ========================================
    // 内部ヘルパー
    // ========================================

    private SupporterApplicationEntity findPendingApplicationOrThrow(
            Long applicationId, String scopeType, Long scopeId) {
        SupporterApplicationEntity app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new BusinessException(SupporterErrorCode.SUPPORTER_003));

        // スコープの不一致チェック（他チームの申請を誤操作しないよう保護）
        if (!app.getScopeType().equals(scopeType) || !app.getScopeId().equals(scopeId)) {
            throw new BusinessException(SupporterErrorCode.SUPPORTER_003);
        }

        if (app.getStatus() != SupporterApplicationStatus.PENDING) {
            throw new BusinessException(SupporterErrorCode.SUPPORTER_004);
        }

        return app;
    }
}
