package com.mannschaft.app.corkboard.service;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * F09.8.1 Phase 3 参照先タイプ別の閲覧権限バッチ判定ディスパッチャ。
 *
 * <p>設計書 §5.2 の {@code AccessControlService.filterAccessible(userId, type, ids)} 相当を担う。
 * type ごとに {@link ContentVisibilityChecker} へ委譲し、閲覧権限判定を実施する。</p>
 *
 * <h3>F00 Phase D-ε 完了（2026-05-05）</h3>
 * <p>共通 {@code ContentVisibilityChecker} の完成に伴い、暫定の保守的フォールバック（全 ID 閲覧不可）
 * から本実装（{@link ContentVisibilityChecker#filterAccessible} への委譲）に切り替えた。
 * 未対応の {@link ReferenceType} は fail-closed で空集合を返す（WARN ログあり）。</p>
 *
 * <h3>論理削除判定について</h3>
 * <p>{@link #filterDeleted} は引き続き暫定実装（常に空集合）のままとする。
 * 共通基盤完成後に type 別 Repository の {@code existsByIdAndDeletedAtIsNull} 等を組み込む。</p>
 */
@Slf4j
@Component
public class AccessControlDispatcher {

    private final ContentVisibilityChecker contentVisibilityChecker;

    public AccessControlDispatcher(ContentVisibilityChecker contentVisibilityChecker) {
        this.contentVisibilityChecker = contentVisibilityChecker;
    }

    /**
     * 指定タイプ・ID 集合のうち、ユーザーが閲覧可能な ID 集合を返す（バッチ判定）。
     *
     * <p>{@link ContentVisibilityChecker#filterAccessible} に委譲する。
     * 未対応の {@code refType} 文字列は fail-closed で空集合を返し WARN ログを出力する。</p>
     *
     * @param userId  ユーザーID
     * @param refType 参照タイプ（{@link ReferenceType#name()} と一致する文字列）
     * @param ids     判定対象 ID 集合
     * @return 閲覧可能な ID 集合
     */
    public Set<Long> filterAccessible(Long userId, String refType, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        try {
            ReferenceType type = ReferenceType.valueOf(refType);
            return contentVisibilityChecker.filterAccessible(type, ids, userId);
        } catch (IllegalArgumentException e) {
            log.warn("AccessControlDispatcher: 未対応の referenceType={} — fail-closed で空集合を返す", refType);
            return Set.of();
        }
    }

    /**
     * 指定タイプ・ID 集合のうち、論理削除済みの ID 集合を返す（バッチ判定）。
     *
     * <p><strong>暫定実装</strong>: 常に空集合を返す（= 削除なし扱い）。
     * 参照先閲覧権限は {@link ContentVisibilityChecker} で判定されるため、削除フラグの値は
     * 実質的に UI 表示へ影響しない。共通基盤完成後に type 別 Repository の
     * {@code findAllByIdInAndDeletedAtIsNotNull} 等を組み込む。</p>
     *
     * @param refType 参照タイプ
     * @param ids     判定対象 ID 集合
     * @return 論理削除済みの ID 集合（暫定実装中は常に空集合）
     */
    public Set<Long> filterDeleted(String refType, Collection<Long> ids) {
        return Set.of();
    }

    /**
     * type ごとに ID リストをまとめて閲覧権限判定し、その結果をまとめて返す。
     *
     * <p>{@link ContentVisibilityChecker#filterAccessibleByType} に委譲する。
     * 未対応の {@code refType} 文字列はスキップし WARN ログを出力する。</p>
     *
     * @param userId    ユーザーID
     * @param idsByType 参照タイプ別 ID リスト
     * @return 参照タイプ別「閲覧可能 ID 集合」マップ
     */
    public Map<String, Set<Long>> filterAccessibleByType(Long userId, Map<String, Set<Long>> idsByType) {
        if (idsByType == null || idsByType.isEmpty()) {
            return Map.of();
        }
        Map<ReferenceType, Set<Long>> typed = new java.util.EnumMap<>(ReferenceType.class);
        for (Map.Entry<String, Set<Long>> entry : idsByType.entrySet()) {
            try {
                ReferenceType type = ReferenceType.valueOf(entry.getKey());
                if (!entry.getValue().isEmpty()) {
                    typed.put(type, entry.getValue());
                }
            } catch (IllegalArgumentException e) {
                log.warn("AccessControlDispatcher: 未対応の referenceType={} をスキップ", entry.getKey());
            }
        }
        Map<ReferenceType, Set<Long>> result = contentVisibilityChecker.filterAccessibleByType(typed, userId);
        // ReferenceType → String に戻して返す
        Map<String, Set<Long>> stringResult = new HashMap<>();
        for (Map.Entry<ReferenceType, Set<Long>> e : result.entrySet()) {
            stringResult.put(e.getKey().name(), e.getValue());
        }
        return stringResult;
    }

    /**
     * type ごとに ID リストをまとめて論理削除判定し、その結果をまとめて返す。
     */
    public Map<String, Set<Long>> filterDeletedByType(Map<String, Set<Long>> idsByType) {
        Map<String, Set<Long>> result = new HashMap<>();
        for (Map.Entry<String, Set<Long>> entry : idsByType.entrySet()) {
            result.put(entry.getKey(), filterDeleted(entry.getKey(), entry.getValue()));
        }
        return result;
    }
}
