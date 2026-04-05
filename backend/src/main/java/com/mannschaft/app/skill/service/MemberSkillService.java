package com.mannschaft.app.skill.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.skill.SkillErrorCode;
import com.mannschaft.app.skill.SkillStatus;
import com.mannschaft.app.skill.entity.MemberSkillEntity;
import com.mannschaft.app.skill.entity.SkillCategoryEntity;
import com.mannschaft.app.skill.event.SkillRegisteredEvent;
import com.mannschaft.app.skill.event.SkillVerifiedEvent;
import com.mannschaft.app.skill.repository.MemberSkillQueryRepository;
import com.mannschaft.app.skill.repository.MemberSkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * メンバースキル・資格管理サービス。
 * 資格の登録・取得・更新・論理削除・承認を担う。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MemberSkillService {

    private final MemberSkillRepository memberSkillRepository;
    private final MemberSkillQueryRepository memberSkillQueryRepository;
    private final SkillCategoryService skillCategoryService;
    private final DomainEventPublisher eventPublisher;

    /**
     * ユーザー自身の資格一覧を取得する。
     *
     * @param userId    ユーザーID
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 資格エンティティ一覧
     */
    public ApiResponse<List<MemberSkillEntity>> getMySkills(
            Long userId, String scopeType, Long scopeId) {
        List<MemberSkillEntity> skills =
                memberSkillRepository.findByUserIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                        userId, scopeType, scopeId);
        return ApiResponse.of(skills);
    }

    /**
     * 資格を新規登録する。
     * - skill_category_id 存在・isActive 確認
     * - 同一 userId + categoryId + name 重複チェック
     * - status=PENDING_REVIEW でINSERT
     * - SkillRegisteredEvent 発行
     *
     * @param userId           ユーザーID
     * @param scopeType        スコープ種別
     * @param scopeId          スコープID
     * @param skillCategoryId  カテゴリID（任意）
     * @param name             資格名
     * @param issuer           発行機関（任意）
     * @param credentialNumber 資格番号（任意）
     * @param acquiredOn       取得日（任意）
     * @param expiresAt        有効期限（任意）
     * @return 登録した資格エンティティ
     */
    @Transactional
    public ApiResponse<MemberSkillEntity> registerSkill(
            Long userId, String scopeType, Long scopeId,
            Long skillCategoryId, String name, String issuer,
            String credentialNumber, LocalDate acquiredOn, LocalDate expiresAt) {

        // カテゴリID指定がある場合は存在・isActive確認
        String categoryName = null;
        if (skillCategoryId != null) {
            SkillCategoryEntity category = skillCategoryService.findCategoryOrThrow(skillCategoryId);
            if (!Boolean.TRUE.equals(category.getIsActive())) {
                throw new BusinessException(SkillErrorCode.SKILL_001);
            }
            categoryName = category.getName();
        }

        // 重複チェック（同一 userId + categoryId + name）
        int duplicateCount = memberSkillQueryRepository.countByUserIdAndScopeAndCategoryAndName(
                userId, scopeType, scopeId, skillCategoryId, name);
        if (duplicateCount > 0) {
            throw new BusinessException(SkillErrorCode.SKILL_005);
        }

        MemberSkillEntity entity = MemberSkillEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .skillCategoryId(skillCategoryId)
                .name(name)
                .issuer(issuer)
                .credentialNumber(credentialNumber)
                .acquiredOn(acquiredOn)
                .expiresAt(expiresAt)
                .status(SkillStatus.PENDING_REVIEW)
                .build();

        MemberSkillEntity saved = memberSkillRepository.save(entity);

        // SkillRegisteredEvent 発行
        eventPublisher.publish(new SkillRegisteredEvent(
                saved.getId(), userId, skillCategoryId, categoryName, name, scopeType, scopeId));

        log.info("資格登録: id={}, userId={}, scope={}/{}, name={}",
                saved.getId(), userId, scopeType, scopeId, name);
        return ApiResponse.of(saved);
    }

    /**
     * 資格詳細を取得する。本人または ADMIN のみアクセス可。
     *
     * @param id            資格ID
     * @param requestUserId リクエストユーザーID
     * @param userRole      ユーザーロール文字列
     * @return 資格エンティティ
     */
    public ApiResponse<MemberSkillEntity> getSkill(
            Long id, Long requestUserId, String userRole) {
        MemberSkillEntity skill = findSkillOrThrow(id);

        if (!skill.getUserId().equals(requestUserId) && !isAdmin(userRole)) {
            throw new BusinessException(SkillErrorCode.SKILL_003);
        }
        return ApiResponse.of(skill);
    }

    /**
     * 資格情報を更新する。本人または ADMIN のみ。バージョンチェックあり。
     *
     * @param id               資格ID
     * @param requestUserId    リクエストユーザーID
     * @param userRole         ユーザーロール文字列
     * @param name             更新後資格名（nullの場合は変更なし）
     * @param issuer           更新後発行機関（nullの場合は変更なし）
     * @param credentialNumber 更新後資格番号（nullの場合は変更なし）
     * @param acquiredOn       更新後取得日（nullの場合は変更なし）
     * @param expiresAt        更新後有効期限（nullの場合は変更なし）
     * @param version          楽観的ロック用バージョン
     * @return 更新後資格エンティティ
     */
    @Transactional
    public ApiResponse<MemberSkillEntity> updateSkill(
            Long id, Long requestUserId, String userRole,
            String name, String issuer, String credentialNumber,
            LocalDate acquiredOn, LocalDate expiresAt, Long version) {

        MemberSkillEntity skill = findSkillOrThrow(id);

        // 権限チェック（本人または ADMIN）
        if (!skill.getUserId().equals(requestUserId) && !isAdmin(userRole)) {
            throw new BusinessException(SkillErrorCode.SKILL_003);
        }

        // バージョンチェック
        if (!skill.getVersion().equals(version)) {
            throw new BusinessException(SkillErrorCode.SKILL_006);
        }

        MemberSkillEntity updated = skill.toBuilder()
                .name(name != null ? name : skill.getName())
                .issuer(issuer != null ? issuer : skill.getIssuer())
                .credentialNumber(credentialNumber != null ? credentialNumber : skill.getCredentialNumber())
                .acquiredOn(acquiredOn != null ? acquiredOn : skill.getAcquiredOn())
                .expiresAt(expiresAt != null ? expiresAt : skill.getExpiresAt())
                .build();

        MemberSkillEntity saved = memberSkillRepository.save(updated);
        log.info("資格更新: id={}", id);
        return ApiResponse.of(saved);
    }

    /**
     * 資格を論理削除する。本人または ADMIN のみ。
     *
     * @param id            資格ID
     * @param requestUserId リクエストユーザーID
     * @param userRole      ユーザーロール文字列
     */
    @Transactional
    public void deleteSkill(Long id, Long requestUserId, String userRole) {
        MemberSkillEntity skill = findSkillOrThrow(id);

        if (!skill.getUserId().equals(requestUserId) && !isAdmin(userRole)) {
            throw new BusinessException(SkillErrorCode.SKILL_003);
        }

        skill.softDelete();
        memberSkillRepository.save(skill);
        log.info("資格論理削除: id={}, userId={}", id, requestUserId);
    }

    /**
     * 資格を承認する（PENDING_REVIEW → ACTIVE）。
     * - PENDING_REVIEW であることを確認
     * - status=ACTIVE に更新、verified_at / verified_by を設定
     * - SkillVerifiedEvent 発行
     *
     * @param id          資格ID
     * @param adminUserId 承認者ユーザーID
     * @return 承認後の資格エンティティ
     */
    @Transactional
    public ApiResponse<MemberSkillEntity> verifySkill(Long id, Long adminUserId) {
        MemberSkillEntity skill = findSkillOrThrow(id);

        if (skill.getStatus() != SkillStatus.PENDING_REVIEW) {
            throw new BusinessException(SkillErrorCode.SKILL_007);
        }

        skill.verify(adminUserId);
        MemberSkillEntity saved = memberSkillRepository.save(skill);

        // SkillVerifiedEvent 発行
        eventPublisher.publish(new SkillVerifiedEvent(
                saved.getId(), saved.getUserId(), adminUserId, saved.getName()));

        log.info("資格承認: id={}, approvedBy={}", id, adminUserId);
        return ApiResponse.of(saved);
    }

    // ========================================
    // 内部メソッド
    // ========================================

    private MemberSkillEntity findSkillOrThrow(Long id) {
        return memberSkillRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessException(SkillErrorCode.SKILL_002));
    }

    private boolean isAdmin(String userRole) {
        return userRole != null &&
                (userRole.equalsIgnoreCase("ADMIN") || userRole.equalsIgnoreCase("DEPUTY_ADMIN"));
    }
}
