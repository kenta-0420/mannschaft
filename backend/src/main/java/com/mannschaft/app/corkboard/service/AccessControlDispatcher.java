package com.mannschaft.app.corkboard.service;

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
 * type ごとに既存の権限チェッカー（{@code TimelinePostAccessChecker} 等）へディスパッチする
 * ことを将来想定とする。</p>
 *
 * <h3>★ 暫定方針: 保守的フォールバック（2026-05-04 適用）</h3>
 * <p>共通 {@code ContentVisibilityResolver} の大改修プロジェクトが別タスクで進行中（推定 3 週間）。
 * その完成までの繋ぎとして、本ディスパッチャは <strong>全ての参照タイプについて
 * {@code is_accessible = false}（空 Set）を返す保守的フォールバック</strong> を適用する。</p>
 * <p>これにより、ダッシュボード上の参照リンクは全カードが「閲覧権限なし」表示・ナビゲーション無効
 * となる。MEMO / SECTION_HEADER / URL カードは参照先解決を必要としない（呼び出し側で
 * 除外済み）ため影響を受けない。</p>
 *
 * <h3>論理削除判定について</h3>
 * <p>{@link #filterDeleted} も同方針で MVP では「常に非削除」を返す。
 * 共通基盤完成後に type 別 Repository の {@code existsByIdAndDeletedAtIsNull} 等を組み込む。</p>
 *
 * <p><strong>TODO: 共通 ContentVisibilityResolver 完成後に置換すること（F00 別軍議）。</strong>
 * 完成後は {@link #filterAccessible} で共通 Resolver 呼び出しに切り替え、本クラスの保守的
 * フォールバックは撤去する。</p>
 */
@Slf4j
@Component
public class AccessControlDispatcher {

    /**
     * 指定タイプ・ID 集合のうち、ユーザーが閲覧可能な ID 集合を返す（バッチ判定）。
     *
     * <p><strong>暫定実装（保守的フォールバック）</strong>:
     * 共通 ContentVisibilityResolver 完成までの繋ぎとして、全ての参照タイプについて
     * 空集合（= 全 ID 閲覧不可）を返す。WARN ログを出力し、運用時に切替忘れを検知できる
     * ようにする。</p>
     *
     * @param userId ユーザーID（将来 Resolver 呼び出しに使用）
     * @param refType 参照タイプ
     * @param ids 判定対象 ID 集合
     * @return 閲覧可能な ID 集合（保守的フォールバック中は常に空集合）
     */
    public Set<Long> filterAccessible(Long userId, String refType, Collection<Long> ids) {
        if (refType == null || ids == null || ids.isEmpty()) {
            return Set.of();
        }
        log.warn("ContentVisibilityResolver 共通基盤未完成のため保守的フォールバック適用: type={}, ids数={}, userId={}",
                refType, ids.size(), userId);
        return Set.of();
    }

    /**
     * 指定タイプ・ID 集合のうち、論理削除済みの ID 集合を返す（バッチ判定）。
     *
     * <p><strong>暫定実装</strong>: 常に空集合を返す（= 削除なし扱い）。
     * 参照先閲覧権限を保守的に false としているため、削除フラグの値は実質的に
     * UI 表示へ影響しない。共通基盤完成後に type 別 Repository の
     * {@code findAllByIdInAndDeletedAtIsNotNull} 等を組み込む。</p>
     *
     * @param refType 参照タイプ
     * @param ids 判定対象 ID 集合
     * @return 論理削除済みの ID 集合（暫定実装中は常に空集合）
     */
    public Set<Long> filterDeleted(String refType, Collection<Long> ids) {
        return Set.of();
    }

    /**
     * type ごとに ID リストをまとめて閲覧権限判定し、その結果をまとめて返す。
     *
     * @param userId   ユーザーID
     * @param idsByType 参照タイプ別 ID リスト
     * @return 参照タイプ別「閲覧可能 ID 集合」マップ（保守的フォールバック中は全 type で空集合）
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
