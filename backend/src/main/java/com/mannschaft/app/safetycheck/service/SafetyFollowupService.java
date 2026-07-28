package com.mannschaft.app.safetycheck.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.safetycheck.FollowupStatus;
import com.mannschaft.app.safetycheck.SafetyCheckErrorCode;
import com.mannschaft.app.safetycheck.SafetyCheckMapper;
import com.mannschaft.app.safetycheck.SafetyCheckScopeType;
import com.mannschaft.app.safetycheck.dto.FollowupUpdateRequest;
import com.mannschaft.app.safetycheck.dto.SafetyFollowupResponse;
import com.mannschaft.app.safetycheck.entity.SafetyCheckEntity;
import com.mannschaft.app.safetycheck.entity.SafetyResponseEntity;
import com.mannschaft.app.safetycheck.entity.SafetyResponseFollowupEntity;
import com.mannschaft.app.safetycheck.repository.SafetyCheckRepository;
import com.mannschaft.app.safetycheck.repository.SafetyResponseFollowupRepository;
import com.mannschaft.app.safetycheck.repository.SafetyResponseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 安否確認フォローアップサービス。要支援者フォローアップの更新を担当する。
 *
 * <p>従来 {@code SafetyFollowupController} が {@code SafetyResponseFollowupRepository} を直接注入し、
 * Service 層も認可も存在しないまま更新していた構造を解消するために新設した。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SafetyFollowupService {

    private final SafetyResponseFollowupRepository followupRepository;
    private final SafetyResponseRepository responseRepository;
    private final SafetyCheckRepository safetyCheckRepository;
    private final SafetyCheckMapper mapper;
    private final AccessControlService accessControlService;

    /**
     * フォローアップを更新する。
     *
     * <p><b>認可（BOLA 封鎖）</b>: URL にスコープを持たない bare id EP のため、
     * {@code followup → safety_response → safety_check} と辿って
     * <b>entity 由来のスコープ</b>を解決し、そのスコープの ADMIN/DEPUTY_ADMIN のみ許可する
     * （同ドメインの {@code SafetyCheckService#closeSafetyCheck} /
     * {@code #getResults} / {@code #getUnrespondedUsers} と同じ束3 AC-1-4 の作法）。
     * 判定は二段階:</p>
     * <ol>
     *   <li>スコープ非メンバー（部外者・別団体の ADMIN による越境）および親を辿れない場合は
     *       {@code FOLLOWUP_NOT_FOUND}（404）に収束させ、要支援者レコードの存在を秘匿する。</li>
     *   <li>スコープのメンバーだが ADMIN/DEPUTY_ADMIN でない場合は 403（{@code ACCESS_DENIED}）。</li>
     * </ol>
     *
     * <p>番人テスト {@code AuthzControllerGuardArchTest} は Controller 起点で 2 ホップまでしか
     * 委譲を辿らないため、{@code accessControlService} は本メソッドから<b>直接</b>呼ぶこと。</p>
     *
     * @param followupId  フォローアップID
     * @param request     更新リクエスト
     * @param actorUserId 操作者ID
     * @return 更新後のフォローアップ
     */
    @Transactional
    public SafetyFollowupResponse updateFollowup(Long followupId, FollowupUpdateRequest request, Long actorUserId) {
        SafetyResponseFollowupEntity entity = followupRepository.findById(followupId)
                .orElseThrow(() -> new BusinessException(SafetyCheckErrorCode.FOLLOWUP_NOT_FOUND));

        SafetyCheckEntity check = resolveSafetyCheckOrHide(entity);
        if (check.getScopeType() == SafetyCheckScopeType.GROUP
                || actorUserId == null
                || !accessControlService.isMember(
                        actorUserId, check.getScopeId(), check.getScopeType().name())) {
            throw new BusinessException(SafetyCheckErrorCode.FOLLOWUP_NOT_FOUND);
        }
        if (!accessControlService.isAdminOrAbove(
                actorUserId, check.getScopeId(), check.getScopeType().name())) {
            throw new BusinessException(SafetyCheckErrorCode.ACCESS_DENIED);
        }

        FollowupStatus status = request.getFollowupStatus() != null
                ? FollowupStatus.valueOf(request.getFollowupStatus()) : null;

        entity.update(status, request.getAssignedTo(), request.getNote());
        entity = followupRepository.save(entity);

        log.info("フォローアップ更新: id={}, status={}, updatedBy={}",
                followupId, entity.getFollowupStatus(), actorUserId);
        return mapper.toFollowupResponse(entity);
    }

    // --- プライベートメソッド ---

    /**
     * フォローアップの親（回答 → 安否確認）を辿ってスコープ保持 entity を解決する。
     * 辿れない場合は存在秘匿のため {@code FOLLOWUP_NOT_FOUND}（404）を送出する。
     */
    private SafetyCheckEntity resolveSafetyCheckOrHide(SafetyResponseFollowupEntity followup) {
        SafetyResponseEntity response = responseRepository.findById(followup.getSafetyResponseId())
                .orElseThrow(() -> new BusinessException(SafetyCheckErrorCode.FOLLOWUP_NOT_FOUND));
        return safetyCheckRepository.findById(response.getSafetyCheckId())
                .orElseThrow(() -> new BusinessException(SafetyCheckErrorCode.FOLLOWUP_NOT_FOUND));
    }
}
