package com.mannschaft.app.skill.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.skill.entity.MemberSkillEntity;
import com.mannschaft.app.skill.repository.MemberSkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * スキル・資格 CSV エクスポートサービス。
 * 非同期でスコープ内の全資格データをCSV形式に変換する。
 * <p>
 * TODO: Phase 2 で S3 保存とジョブ進捗管理を実装する。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillCsvService {

    private final MemberSkillRepository memberSkillRepository;
    private final SkillCategoryService skillCategoryService;
    private final NameResolverService nameResolverService;

    /**
     * スコープ内の全資格データを非同期でCSVエクスポートする。
     * <p>
     * 列定義: ユーザーID, 表示名, カテゴリ名, 資格名, 発行機関, 資格番号, 取得日, 有効期限, ステータス
     * </p>
     * <p>
     * TODO: Phase 2 で S3 保存とジョブ進捗管理を実装する（現時点はログ出力のみ）。
     * </p>
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param requestedBy リクエスト実行者ユーザーID
     */
    @Async("job-pool")
    @Transactional(readOnly = true)
    public void exportAsync(String scopeType, Long scopeId, Long requestedBy) {
        log.info("スキルCSVエクスポート開始: scope={}/{}, requestedBy={}", scopeType, scopeId, requestedBy);

        try {
            // 1. スコープ内の全 member_skills 取得（deleted_at IS NULL）
            List<MemberSkillEntity> skills = memberSkillRepository.findAll().stream()
                    .filter(s -> s.getScopeType().equals(scopeType)
                            && s.getScopeId().equals(scopeId)
                            && s.getDeletedAt() == null)
                    .toList();

            // 2. ユーザー表示名を一括解決（N+1 回避）
            List<Long> userIds = skills.stream().map(MemberSkillEntity::getUserId).distinct().toList();
            Map<Long, String> displayNames = nameResolverService.resolveUserDisplayNames(userIds);

            // 3. カテゴリ名マップ構築
            Map<Long, String> categoryNames = skillCategoryService
                    .getCategories(scopeType, scopeId, true)
                    .getData()
                    .stream()
                    .collect(Collectors.toMap(
                            c -> c.getId(),
                            c -> c.getName(),
                            (a, b) -> a));

            // 4. CSV文字列を StringBuilderで組み立て
            StringBuilder csv = new StringBuilder();
            csv.append('\uFEFF'); // BOM（Excel対応）
            csv.append("ユーザーID,表示名,カテゴリ名,資格名,発行機関,資格番号,取得日,有効期限,ステータス\n");

            for (MemberSkillEntity skill : skills) {
                String displayName = displayNames.getOrDefault(skill.getUserId(), "不明なユーザー");
                String categoryName = skill.getSkillCategoryId() != null
                        ? categoryNames.getOrDefault(skill.getSkillCategoryId(), "")
                        : "";

                csv.append(skill.getUserId()).append(',');
                csv.append(escapeCsv(displayName)).append(',');
                csv.append(escapeCsv(categoryName)).append(',');
                csv.append(escapeCsv(skill.getName())).append(',');
                csv.append(escapeCsv(skill.getIssuer())).append(',');
                csv.append(escapeCsv(skill.getCredentialNumber())).append(',');
                csv.append(skill.getAcquiredOn() != null ? skill.getAcquiredOn().toString() : "").append(',');
                csv.append(skill.getExpiresAt() != null ? skill.getExpiresAt().toString() : "").append(',');
                csv.append(skill.getStatus().name()).append('\n');
            }

            // 5. エクスポート完了をログに記録
            // TODO: Phase 2 で S3 保存とジョブ進捗管理を実装する
            log.info("スキルCSVエクスポート完了: scope={}/{}, rows={}, requestedBy={}",
                    scopeType, scopeId, skills.size(), requestedBy);
            log.debug("CSVプレビュー(先頭500文字): {}", csv.substring(0, Math.min(500, csv.length())));

        } catch (Exception e) {
            log.error("スキルCSVエクスポート失敗: scope={}/{}, requestedBy={}",
                    scopeType, scopeId, requestedBy, e);
        }
    }

    // ========================================
    // ヘルパー
    // ========================================

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
