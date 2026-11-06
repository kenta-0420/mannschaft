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
            // 所属スコープ（種別・ID の両方）が登録先と一致しないカテゴリは、他チーム・異種スコープ・非active でも
            // 不在と同一の SKILL_001 で秘匿する。active 判定より必ず先に行う（CMP-261010-2226）。
            if (!category.getScopeType().equals(scopeType) || !scopeId.equals(category.getScopeId())) {
                throw new BusinessException(SkillErrorCode.SKILL_001);
            }
            // 実在するが受け付けない状態（非アクティブ）は不在（SKILL_001・404）と区別して 409。
            if (!Boolean.TRUE.equals(category.getIsActive())) {
                throw new BusinessException(SkillErrorCode.SKILL_010);
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
     * 認可前に必要な資格の所属情報（スコープ・所有者・証明書の有無）を解決する。
     * 認可は本サービスではなく非 tx の {@code MemberSkillFacade} が行う（CMP-261010-2226）。
     * 資格の不在・削除済み、および資格の所属スコープがリクエスト元と不一致（越境）の場合は、
     * どちらも同一の SKILL_002 を投げて存在を秘匿する。
     *
     * @param id        資格ID
     * @param scopeType リクエスト元のスコープ種別（コントローラの path teamId から解決）
     * @param scopeId   リクエスト元のスコープID
     * @return 資格の所属情報
     */
    public SkillAccessScope resolveAccessScope(Long id, String scopeType, Long scopeId) {
        MemberSkillEntity skill = findSkillOrThrow(id);
        checkScopeOrThrow(skill, scopeType, scopeId);
        return new SkillAccessScope(skill.getScopeId(), skill.getUserId(), skill.getCertificateS3Key());
    }

    /**
     * 認可前に解決した資格の所属情報。
     *
     * @param scopeId          資格の所属スコープID（資格実体から解決した値）
     * @param ownerUserId      資格の所有者ユーザーID
     * @param certificateS3Key 証明書の保存キー（未登録なら null）
     */
    public record SkillAccessScope(Long scopeId, Long ownerUserId, String certificateS3Key) {
    }

    /**
     * 資格詳細を取得する。認可は呼び出し元の {@code MemberSkillFacade} で済んでいる前提。
     * BOLA対策として、資格の所属スコープ（scopeType/scopeId）がリクエスト元の teamId と一致することを確認し、
     * 不一致（越境アクセス）は SKILL_002 で不在と同一に秘匿する。
     *
     * @param id        資格ID
     * @param scopeType リクエスト元のスコープ種別（コントローラの path teamId から解決）
     * @param scopeId   リクエスト元のスコープID
     * @return 資格エンティティ
     */
    public ApiResponse<MemberSkillEntity> getSkill(Long id, String scopeType, Long scopeId) {
        MemberSkillEntity skill = findSkillOrThrow(id);
        checkScopeOrThrow(skill, scopeType, scopeId);
        return ApiResponse.of(skill);
    }

    /**
     * 資格情報を更新する。バージョンチェックあり（認可は呼び出し元の {@code MemberSkillFacade} で済んでいる前提）。
     *
     * @param id               資格ID
     * @param scopeType        リクエスト元のスコープ種別（コントローラの path teamId から解決）
     * @param scopeId          リクエスト元のスコープID
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
            Long id, String scopeType, Long scopeId,
            String name, String issuer, String credentialNumber,
            LocalDate acquiredOn, LocalDate expiresAt, Long version) {

        MemberSkillEntity skill = findSkillOrThrow(id);
        checkScopeOrThrow(skill, scopeType, scopeId);

        // バージョンチェック
        if (!skill.getVersion().equals(version)) {
            throw new BusinessException(SkillErrorCode.SKILL_006);
        }

        // managed エンティティを直接ミューテートして id を保持したまま UPDATE を発行する
        // （toBuilder().build()→save は継承フィールド id を引き継がず INSERT 化するため廃止）
        skill.applyUpdate(
                name != null ? name : skill.getName(),
                issuer != null ? issuer : skill.getIssuer(),
                credentialNumber != null ? credentialNumber : skill.getCredentialNumber(),
                acquiredOn != null ? acquiredOn : skill.getAcquiredOn(),
                expiresAt != null ? expiresAt : skill.getExpiresAt()
        );

        MemberSkillEntity saved = memberSkillRepository.save(skill);
        log.info("資格更新: id={}", id);
        return ApiResponse.of(saved);
    }

    /**
     * 資格を論理削除する（認可は呼び出し元の {@code MemberSkillFacade} で済んでいる前提）。
     *
     * @param id            資格ID
     * @param requestUserId 操作者ユーザーID（ログ用）
     * @param scopeType     リクエスト元のスコープ種別（コントローラの path teamId から解決）
     * @param scopeId       リクエスト元のスコープID
     */
    @Transactional
    public void deleteSkill(Long id, Long requestUserId, String scopeType, Long scopeId) {
        MemberSkillEntity skill = findSkillOrThrow(id);
        checkScopeOrThrow(skill, scopeType, scopeId);

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
     * @param scopeType   リクエスト元のスコープ種別（コントローラの path teamId から解決）
     * @param scopeId     リクエスト元のスコープID
     * @return 承認後の資格エンティティ
     */
    @Transactional
    public ApiResponse<MemberSkillEntity> verifySkill(Long id, Long adminUserId, String scopeType, Long scopeId) {
        MemberSkillEntity skill = findSkillOrThrow(id);
        checkScopeOrThrow(skill, scopeType, scopeId);

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

    /**
     * BOLA対策: 資格が属するスコープ（scopeType/scopeId）がリクエスト元の teamId と一致するか検証する。
     * 不一致の場合は資格不在と同じ SKILL_002 を投げ、他チームの資格IDであることを秘匿する。
     * （手本: {@link SkillCategoryService#updateCategory} の所有スコープ確認）。
     */
    private void checkScopeOrThrow(MemberSkillEntity skill, String scopeType, Long scopeId) {
        if (!skill.getScopeType().equals(scopeType) || !skill.getScopeId().equals(scopeId)) {
            throw new BusinessException(SkillErrorCode.SKILL_002);
        }
    }
}
