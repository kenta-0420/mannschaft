package com.mannschaft.app.corkboard.service;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * F09.8.1 Phase 3 参照先タイプ別の閲覧権限バッチ判定ディスパッチャ。
 *
 * <p>設計書 §5.2 の {@code AccessControlService.filterAccessible(userId, type, ids)} 相当を担う。
 * type ごとに既存の権限チェッカー（{@code TimelinePostAccessChecker} 等）へディスパッチする
 * ことを将来想定とするが、現時点では既存に統一された参照先別 Checker が存在しないため、
 * <strong>Phase 3 の MVP として「対応 type は閲覧可、未対応 type は閲覧不可」フォールバック</strong>
 * を採用する（PR 説明文・設計書 §13 にて明記）。</p>
 *
 * <p>v2.0 で type 別 Checker が整備され次第、本クラス内の {@link #filterAccessible} の
 * switch 文に「実 Checker 呼び出し」を追加する（API スキーマや Service 層への影響なし）。</p>
 *
 * <h3>論理削除判定について</h3>
 * <p>同様に {@link #filterDeleted} は MVP では「常に非削除」を返す。将来 type 別 Repository の
 * {@code existsByIdAndDeletedAtIsNull} 等を組み込む。論理削除済み参照先は
 * {@link ReferenceTypeResolver} で snapshot 表示にフォールバックされる。</p>
 */
@Component
public class AccessControlDispatcher {

    /**
     * 指定タイプ・ID 集合のうち、ユーザーが閲覧可能な ID 集合を返す（バッチ判定）。
     *
     * <p>MVP 実装: 対応 type ({@link ReferenceTypeResolver#SUPPORTED_TYPES}) は全 ID を閲覧可、
     * 未対応 type は空集合を返す。URL タイプは Resolver 側で常に accessible 扱いするため、
     * 本ディスパッチャでは判定対象外（呼び出し側で除外推奨）。</p>
     *
     * @param userId ユーザーID（将来 Checker 呼び出しに使用）
     * @param refType 参照タイプ
     * @param ids 判定対象 ID 集合
     * @return 閲覧可能な ID 集合（呼び出し側に対する読み取り専用ビュー）
     */
    public Set<Long> filterAccessible(Long userId, String refType, Collection<Long> ids) {
        if (refType == null || ids == null || ids.isEmpty()) {
            return Set.of();
        }
        if (!ReferenceTypeResolver.SUPPORTED_TYPES.contains(refType) || "URL".equals(refType)) {
            return Set.of();
        }
        // MVP: 対応 type は全 ID を閲覧可とする（type 別 Checker 整備後に置換）
        return new HashSet<>(ids);
    }

    /**
     * 指定タイプ・ID 集合のうち、論理削除済みの ID 集合を返す（バッチ判定）。
     *
     * <p>MVP 実装: 常に空集合を返す。type 別 Repository での
     * {@code findAllByIdInAndDeletedAtIsNotNull} 整備後に置換。</p>
     *
     * @param refType 参照タイプ
     * @param ids 判定対象 ID 集合
     * @return 論理削除済みの ID 集合
     */
    public Set<Long> filterDeleted(String refType, Collection<Long> ids) {
        // MVP: 削除判定は v2.0 で実装。現時点では常に「未削除」扱い。
        return Set.of();
    }

    /**
     * type ごとに ID リストをまとめて閲覧権限判定し、その結果をまとめて返す。
     *
     * @param userId   ユーザーID
     * @param idsByType 参照タイプ別 ID リスト
     * @return 参照タイプ別「閲覧可能 ID 集合」マップ
     */
    public Map<String, Set<Long>> filterAccessibleByType(Long userId, Map<String, Set<Long>> idsByType) {
        Map<String, Set<Long>> result = new HashMap<>();
        for (Map.Entry<String, Set<Long>> entry : idsByType.entrySet()) {
            result.put(entry.getKey(), filterAccessible(userId, entry.getKey(), entry.getValue()));
        }
        return result;
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
