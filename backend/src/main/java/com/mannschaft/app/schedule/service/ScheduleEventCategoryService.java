package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.ScheduleEventCategoryErrorCode;
import com.mannschaft.app.schedule.entity.ScheduleEventCategoryEntity;
import com.mannschaft.app.schedule.repository.ScheduleEventCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * スケジュール行事カテゴリ管理サービス。
 * チーム・組織スコープのカテゴリ CRUD とプリセット初期化を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleEventCategoryService {

    private final ScheduleEventCategoryRepository categoryRepository;

    /** カテゴリ上限数 */
    private static final int MAX_CATEGORIES = 30;

    /**
     * プリセット定義: テンプレート種別 → カテゴリリスト (name, color, isDayOff)
     */
    private static final Map<String, List<PresetCategory>> PRESET_DEFINITIONS = Map.of(
            "クラス", List.of(
                    new PresetCategory("式典", "#EF4444", false),
                    new PresetCategory("テスト・試験", "#F59E0B", false),
                    new PresetCategory("スポーツ行事", "#10B981", false),
                    new PresetCategory("文化行事", "#8B5CF6", false),
                    new PresetCategory("保護者会", "#EC4899", false),
                    new PresetCategory("休業日", "#6B7280", true),
                    new PresetCategory("遠足・校外学習", "#06B6D4", false)
            ),
            "スポーツ", List.of(
                    new PresetCategory("大会", "#EF4444", false),
                    new PresetCategory("合宿", "#F59E0B", false),
                    new PresetCategory("練習試合", "#10B981", false),
                    new PresetCategory("オフ日", "#6B7280", true)
            ),
            "会社", List.of(
                    new PresetCategory("全社行事", "#3B82F6", false),
                    new PresetCategory("研修", "#8B5CF6", false),
                    new PresetCategory("休業日", "#6B7280", true),
                    new PresetCategory("株主総会", "#EF4444", false)
            ),
            "マンション", List.of(
                    new PresetCategory("総会", "#EF4444", false),
                    new PresetCategory("点検・工事", "#F59E0B", false),
                    new PresetCategory("清掃", "#10B981", false),
                    new PresetCategory("お祭り・イベント", "#8B5CF6", false)
            ),
            "家族", List.of(
                    new PresetCategory("家族旅行", "#06B6D4", false),
                    new PresetCategory("記念日", "#EC4899", false),
                    new PresetCategory("誕生日", "#F59E0B", false),
                    new PresetCategory("家族行事", "#8B5CF6", false),
                    new PresetCategory("お休み", "#6B7280", true)
            )
    );

    /**
     * チーム固有カテゴリ + 親組織カテゴリをマージして取得する。
     *
     * @param teamId         チームID
     * @param organizationId 親組織ID
     * @return マージされたカテゴリ一覧（sortOrder順）
     */
    public List<ScheduleEventCategoryEntity> getCategoriesForTeam(Long teamId, Long organizationId) {
        List<ScheduleEventCategoryEntity> teamCategories =
                categoryRepository.findByTeamIdOrderBySortOrder(teamId);
        List<ScheduleEventCategoryEntity> orgCategories =
                categoryRepository.findByOrganizationIdOrderBySortOrder(organizationId);

        List<ScheduleEventCategoryEntity> merged = new ArrayList<>(orgCategories);
        merged.addAll(teamCategories);
        return merged;
    }

    /**
     * 組織スコープのカテゴリ一覧を取得する。
     *
     * @param orgId 組織ID
     * @return カテゴリ一覧（sortOrder順）
     */
    public List<ScheduleEventCategoryEntity> getCategoriesForOrganization(Long orgId) {
        return categoryRepository.findByOrganizationIdOrderBySortOrder(orgId);
    }

    /**
     * カテゴリをIDで取得する。
     *
     * @param categoryId カテゴリID
     * @return カテゴリエンティティ
     * @throws BusinessException カテゴリが見つからない場合
     */
    public ScheduleEventCategoryEntity getById(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new BusinessException(
                        ScheduleEventCategoryErrorCode.CATEGORY_NOT_FOUND));
    }

    /**
     * チームスコープのカテゴリを作成する。
     *
     * @param teamId チームID
     * @param data   作成データ
     * @return 作成されたカテゴリ
     */
    @Transactional
    public ScheduleEventCategoryEntity createTeamCategory(Long teamId, CreateCategoryData data) {
        validateDuplicateNameForTeam(teamId, data.name());
        validateCategoryLimit(categoryRepository.findByTeamIdOrderBySortOrder(teamId).size());

        ScheduleEventCategoryEntity entity = ScheduleEventCategoryEntity.builder()
                .teamId(teamId)
                .name(data.name())
                .color(data.color())
                .icon(data.icon())
                .isDayOffCategory(data.isDayOffCategory())
                .sortOrder(data.sortOrder())
                .build();

        log.info("チームカテゴリ作成: teamId={}, name={}", teamId, data.name());
        return categoryRepository.save(entity);
    }

    /**
     * 組織スコープのカテゴリを作成する。
     *
     * @param orgId 組織ID
     * @param data  作成データ
     * @return 作成されたカテゴリ
     */
    @Transactional
    public ScheduleEventCategoryEntity createOrgCategory(Long orgId, CreateCategoryData data) {
        validateDuplicateNameForOrg(orgId, data.name());
        validateCategoryLimit(categoryRepository.findByOrganizationIdOrderBySortOrder(orgId).size());

        ScheduleEventCategoryEntity entity = ScheduleEventCategoryEntity.builder()
                .organizationId(orgId)
                .name(data.name())
                .color(data.color())
                .icon(data.icon())
                .isDayOffCategory(data.isDayOffCategory())
                .sortOrder(data.sortOrder())
                .build();

        log.info("組織カテゴリ作成: orgId={}, name={}", orgId, data.name());
        return categoryRepository.save(entity);
    }

    /**
     * カテゴリを更新する。
     *
     * @param categoryId カテゴリID
     * @param data       更新データ
     * @return 更新されたカテゴリ
     */
    @Transactional
    public ScheduleEventCategoryEntity updateCategory(Long categoryId, UpdateCategoryData data) {
        ScheduleEventCategoryEntity existing = getById(categoryId);

        // 名前が変更される場合のみ重複チェック
        if (data.name() != null && !data.name().equals(existing.getName())) {
            if (existing.isTeamScope()) {
                validateDuplicateNameForTeam(existing.getTeamId(), data.name());
            } else {
                validateDuplicateNameForOrg(existing.getOrganizationId(), data.name());
            }
        }

        ScheduleEventCategoryEntity updated = existing.toBuilder()
                .name(data.name() != null ? data.name() : existing.getName())
                .color(data.color() != null ? data.color() : existing.getColor())
                .icon(data.icon() != null ? data.icon() : existing.getIcon())
                .isDayOffCategory(data.isDayOffCategory() != null
                        ? data.isDayOffCategory() : existing.getIsDayOffCategory())
                .sortOrder(data.sortOrder() != null ? data.sortOrder() : existing.getSortOrder())
                .build();

        log.info("カテゴリ更新: categoryId={}", categoryId);
        return categoryRepository.save(updated);
    }

    /**
     * カテゴリを削除する。紐づくスケジュールの event_category_id は DB 側 ON DELETE SET NULL で処理される。
     *
     * @param categoryId カテゴリID
     */
    @Transactional
    public void deleteCategory(Long categoryId) {
        ScheduleEventCategoryEntity existing = getById(categoryId);
        log.info("カテゴリ削除: categoryId={}, name={}", categoryId, existing.getName());
        categoryRepository.delete(existing);
    }

    /**
     * テンプレート種別に応じたプリセットカテゴリを自動作成する。
     *
     * @param scopeId      チームIDまたは組織ID
     * @param isTeam       true ならチームスコープ、false なら組織スコープ
     * @param templateType テンプレート種別（"クラス", "スポーツ", "会社", "マンション", "家族"）
     */
    @Transactional
    public void initializePresets(Long scopeId, boolean isTeam, String templateType) {
        List<PresetCategory> presets = PRESET_DEFINITIONS.get(templateType);
        if (presets == null) {
            log.warn("未定義のテンプレート種別: {}", templateType);
            return;
        }

        for (int i = 0; i < presets.size(); i++) {
            PresetCategory preset = presets.get(i);

            ScheduleEventCategoryEntity.ScheduleEventCategoryEntityBuilder builder =
                    ScheduleEventCategoryEntity.builder()
                            .name(preset.name())
                            .color(preset.color())
                            .isDayOffCategory(preset.isDayOff())
                            .sortOrder(i + 1);

            if (isTeam) {
                builder.teamId(scopeId);
            } else {
                builder.organizationId(scopeId);
            }

            categoryRepository.save(builder.build());
        }

        log.info("プリセットカテゴリ初期化: scopeId={}, isTeam={}, template={}, count={}",
                scopeId, isTeam, templateType, presets.size());
    }

    /**
     * カテゴリスコープの整合性を検証する。
     * スケジュールのスコープ（チーム/組織）とカテゴリのスコープが一致するか確認する。
     *
     * @param scheduleTeamId スケジュールのチームID（チームスコープの場合）
     * @param scheduleOrgId  スケジュールの組織ID（組織スコープの場合）
     * @param categoryId     カテゴリID
     * @throws BusinessException スコープが不一致の場合
     */
    public void validateCategoryScope(Long scheduleTeamId, Long scheduleOrgId, Long categoryId) {
        if (categoryId == null) {
            return;
        }

        ScheduleEventCategoryEntity category = getById(categoryId);

        if (scheduleTeamId != null) {
            // チームスコープ: チームカテゴリまたは親組織カテゴリであること
            if (category.isTeamScope() && !category.getTeamId().equals(scheduleTeamId)) {
                throw new BusinessException(ScheduleEventCategoryErrorCode.CATEGORY_SCOPE_MISMATCH);
            }
            if (category.isOrganizationScope() && scheduleOrgId != null
                    && !category.getOrganizationId().equals(scheduleOrgId)) {
                throw new BusinessException(ScheduleEventCategoryErrorCode.CATEGORY_SCOPE_MISMATCH);
            }
        } else if (scheduleOrgId != null) {
            // 組織スコープ: 組織カテゴリであること
            if (!category.isOrganizationScope()
                    || !category.getOrganizationId().equals(scheduleOrgId)) {
                throw new BusinessException(ScheduleEventCategoryErrorCode.CATEGORY_SCOPE_MISMATCH);
            }
        }
    }

    // ── Private methods ──

    private void validateDuplicateNameForTeam(Long teamId, String name) {
        if (categoryRepository.existsByTeamIdAndName(teamId, name)) {
            throw new BusinessException(ScheduleEventCategoryErrorCode.DUPLICATE_CATEGORY_NAME);
        }
    }

    private void validateDuplicateNameForOrg(Long orgId, String name) {
        if (categoryRepository.existsByOrganizationIdAndName(orgId, name)) {
            throw new BusinessException(ScheduleEventCategoryErrorCode.DUPLICATE_CATEGORY_NAME);
        }
    }

    private void validateCategoryLimit(int currentCount) {
        if (currentCount >= MAX_CATEGORIES) {
            throw new BusinessException(ScheduleEventCategoryErrorCode.CATEGORY_LIMIT_EXCEEDED);
        }
    }

    // ── Inner Records ──

    /**
     * カテゴリ作成データ。
     */
    public record CreateCategoryData(
            String name,
            String color,
            String icon,
            Boolean isDayOffCategory,
            Integer sortOrder
    ) {}

    /**
     * カテゴリ更新データ。
     */
    public record UpdateCategoryData(
            String name,
            String color,
            String icon,
            Boolean isDayOffCategory,
            Integer sortOrder
    ) {}

    /**
     * プリセットカテゴリ定義。
     */
    private record PresetCategory(String name, String color, boolean isDayOff) {}
}
