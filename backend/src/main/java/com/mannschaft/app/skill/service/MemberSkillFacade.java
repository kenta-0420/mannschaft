package com.mannschaft.app.skill.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ScopeConcealingAccessGate;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.skill.SkillErrorCode;
import com.mannschaft.app.skill.SkillMapper;
import com.mannschaft.app.skill.dto.MemberSkillResponse;
import com.mannschaft.app.skill.dto.UpdateSkillRequest;
import com.mannschaft.app.skill.entity.MemberSkillEntity;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 資格 API（個別 ID の取得・更新・削除・証明書 URL）の <b>認可ファサード</b>（トランザクションの外）。
 *
 * <p>CMP-261010-2226: 認可（{@code ScopeConcealingAccessGate}）を {@code @Transactional} の外へ出す。
 * 本クラスは {@code @Transactional} を<b>付けない</b>（クラスにもメソッドにも）。
 * 流れは「{@link MemberSkillService} の readOnly な所属解決 → 親チームの生存確認 → 認可 →
 * {@link MemberSkillService} の tx（中で読み直す）」。{@link MemberSkillService} は認可クラスへ到達させない。</p>
 *
 * <h3>判定順（入れ替えてはならない）</h3>
 * <ol>
 *   <li>資格が不在・論理削除済み、または資格の所属スコープが path と不一致 → 404 SKILL_002</li>
 *   <li>親チームが不在・論理削除済み → 404 SKILL_002（本人・SYSTEM_ADMIN も。ゲートは親の生存を見ないため先に確認する）</li>
 *   <li>{@code requireOwnerOrAdminOrConceal}: 非メンバー（越境）は 404 SKILL_002（不在と完全一致）、
 *       同チームの本人でも管理者でもない所属者は 403 COMMON_002、本人・ADMIN/DEPUTY_ADMIN・SYSTEM_ADMIN は通過</li>
 *   <li>証明書 URL のみ: 認可通過後に証明書キーが未登録なら 404 SKILL_002（storage は呼ばない）</li>
 * </ol>
 * <p>名前を {@code *AccessService} / {@code *AccessGate} にしないのは、呼んだだけで認可シグナル扱いになり
 * 本クラス内の認可漏れを AuthzControllerGuard が見逃すのを避けるため。</p>
 */
@Service
@RequiredArgsConstructor
public class MemberSkillFacade {

    private static final String TEAM = "TEAM";
    private static final Duration CERTIFICATE_URL_TTL = Duration.ofMinutes(15);

    private final MemberSkillService memberSkillService;
    private final SkillCategoryService skillCategoryService;
    private final SkillMapper skillMapper;
    private final TeamService teamService;
    private final ScopeConcealingAccessGate accessGate;
    private final StorageService storageService;

    /**
     * 資格詳細を取得する（本人、または当該チームの ADMIN 以上・SYSTEM_ADMIN）。
     *
     * @param id     資格ID
     * @param userId 操作者ユーザーID
     * @param teamId path の teamId
     * @return 資格詳細
     */
    public MemberSkillResponse getSkill(Long id, Long userId, Long teamId) {
        authorize(id, userId, teamId);
        MemberSkillEntity entity = memberSkillService.getSkill(id, TEAM, teamId).getData();
        return skillMapper.toResponse(entity, resolveCategoryName(entity.getSkillCategoryId(), teamId));
    }

    /**
     * 資格情報を更新する（本人、または当該チームの ADMIN 以上・SYSTEM_ADMIN）。
     *
     * @param id      資格ID
     * @param userId  操作者ユーザーID
     * @param teamId  path の teamId
     * @param request 更新リクエスト
     * @return 更新後の資格
     */
    public MemberSkillResponse updateSkill(Long id, Long userId, Long teamId, UpdateSkillRequest request) {
        authorize(id, userId, teamId);
        MemberSkillEntity entity = memberSkillService.updateSkill(
                id, TEAM, teamId,
                request.name(), request.issuer(), request.credentialNumber(),
                request.acquiredOn(), request.expiresAt(), request.version()
        ).getData();
        return skillMapper.toResponse(entity, resolveCategoryName(entity.getSkillCategoryId(), teamId));
    }

    /**
     * 資格を論理削除する（本人、または当該チームの ADMIN 以上・SYSTEM_ADMIN）。
     *
     * @param id     資格ID
     * @param userId 操作者ユーザーID
     * @param teamId path の teamId
     */
    public void deleteSkill(Long id, Long userId, Long teamId) {
        authorize(id, userId, teamId);
        memberSkillService.deleteSkill(id, userId, TEAM, teamId);
    }

    /**
     * 証明書のダウンロード用 Pre-signed URL を発行する（本人、または当該チームの ADMIN 以上・SYSTEM_ADMIN）。
     * 証明書が未登録なら認可通過後に 404 SKILL_002（storage は呼ばない）。
     *
     * @param id     資格ID
     * @param userId 操作者ユーザーID
     * @param teamId path の teamId
     * @return ダウンロード URL
     */
    public String getCertificateUrl(Long id, Long userId, Long teamId) {
        MemberSkillService.SkillAccessScope scope = authorize(id, userId, teamId);
        String key = scope.certificateS3Key();
        if (key == null || key.isBlank()) {
            throw new BusinessException(SkillErrorCode.SKILL_002);
        }
        return storageService.generateDownloadUrl(key, CERTIFICATE_URL_TTL);
    }

    /**
     * 資格の所属解決 → 親チームの生存確認 → 本人/管理者の認可を行う。
     * 親チームの生存は team ドメインの公開窓口 {@link TeamService#findTeamSummary}（論理削除済みは空）で確認する。
     */
    private MemberSkillService.SkillAccessScope authorize(Long id, Long userId, Long teamId) {
        MemberSkillService.SkillAccessScope scope = memberSkillService.resolveAccessScope(id, TEAM, teamId);
        if (teamService.findTeamSummary(scope.scopeId()).isEmpty()) {
            throw new BusinessException(SkillErrorCode.SKILL_002);
        }
        accessGate.requireOwnerOrAdminOrConceal(
                userId, scope.scopeId(), TEAM, scope.ownerUserId(), SkillErrorCode.SKILL_002);
        return scope;
    }

    private String resolveCategoryName(Long categoryId, Long teamId) {
        if (categoryId == null) {
            return null;
        }
        Map<Long, String> names = skillCategoryService.getCategories(TEAM, teamId, true).getData()
                .stream()
                .collect(Collectors.toMap(c -> c.getId(), c -> c.getName(), (a, b) -> a));
        return names.getOrDefault(categoryId, null);
    }
}
