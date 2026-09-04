package com.mannschaft.app.common.duplicatename;

import java.util.List;
import java.util.function.Supplier;

/**
 * CMP-260901-1538 柱③-A: 組織・チーム作成時の同名確認フローの中核ロジック。
 *
 * <p>既存の {@code existsByName} による一律ブロックを撤去し、代わりに以下の二段方式で
 * 誤重複作成を防止する:</p>
 * <ol>
 *   <li>{@code confirmDuplicate=false}（既定）で同名候補が存在する場合、
 *       {@link DuplicateNameConfirmationRequiredException}（409）を投げて候補一覧＋fingerprint を返す</li>
 *   <li>クライアントが確認のうえ {@code confirmDuplicate=true} と fingerprint を返送すると、
 *       候補集合を再計算し fingerprint を再検証してから作成を許可する。
 *       確認後に新たな同名候補が出現していれば（fingerprint 不一致）再度 409</li>
 * </ol>
 *
 * <h2>検分 P1-2 是正: TOCTOU 対策（設計判断）</h2>
 * <p>組織名・チーム名は一意制約を持たない（同名の併存を許可する設計のため、DB レベルの
 * 一意制約で TOCTOU を機械的に防げない）。そのため本実装は MySQL の
 * <b>名前付きアドバイザリロック（{@code GET_LOCK}/{@code RELEASE_LOCK}）</b>で
 * 「候補再計算 → 作成（INSERT）」区間を <b>同一正規化名の作成者同士だけ</b>直列化する。
 * ロックキーは {@code scopeKind + 正規化名} を SHA-256 でハッシュ化したもの
 * （{@code GET_LOCK} のキー長制限 64 文字に収めるため）。取得タイムアウトは数秒
 * （{@link DuplicateNameGuardServiceImpl#LOCK_TIMEOUT_SECONDS}）とし、トランザクション終了
 * または DB 接続切断で確実に解放される（MySQL のセッションスコープ関数のため）。</p>
 *
 * <p>ロックを取っただけでは InnoDB の REPEATABLE READ スナップショットが古いままの恐れが
 * あるため（呼び出し元が本メソッド呼び出し前に他のクエリを発行しているとスナップショットが
 * 先に確立し得る）、{@code candidateSupplier} は <b>ロッキングリード（{@code FOR UPDATE}
 * 等、{@code PESSIMISTIC_WRITE} 相当）</b>で最新のコミット済みデータを読む契約とする
 * （{@code OrganizationRepository#findActiveByNormalizedNameForUpdate} 等を参照）。</p>
 *
 * <p>{@code createAction}（実際の作成処理）はロック保持中に実行することで、
 * 「判定 → 作成」の間に別の同名作成者が割り込めないようにする。</p>
 */
public interface DuplicateNameGuardService {

    /**
     * 作成前チェックを行い、続行可能なら {@code createAction} をロック保持中に実行してその
     * 結果を返す。同名候補があり確認未完了（または fingerprint 不一致）なら
     * {@link DuplicateNameConfirmationRequiredException}（409）を投げ、{@code createAction}
     * は実行しない。
     *
     * @param scopeKind         スコープ種別（組織/チーム）
     * @param rawName           作成しようとしている名称（trim 前）
     * @param actorUserId       操作者ユーザーID
     * @param confirmDuplicate  クライアントが同名確認済みとして送信したか
     * @param suppliedFingerprint クライアントが返送した fingerprint（confirmDuplicate=false 時は無視）
     * @param candidateSupplier 同名候補を検索するコールバック（trim + utf8mb4_0900_ai_ci 相当の
     *                          {@code =} 比較・<b>ロッキングリード</b>で検索し、可視性ルール適用済みの
     *                          {@link DuplicateNameCandidate} 一覧を返す想定）。アドバイザリロック
     *                          保持中に呼ばれるため、同名作成者間では直列化された最新状態を反映する
     * @param createAction      重複確認を通過した場合に実行する実際の作成処理（アドバイザリロック
     *                          保持中に実行される）
     * @param <T>               {@code createAction} の戻り値型
     * @return {@code createAction} の実行結果
     */
    <T> T checkForCreateAndRun(DuplicateNameScopeKind scopeKind, String rawName, Long actorUserId,
            boolean confirmDuplicate, String suppliedFingerprint,
            Supplier<List<DuplicateNameCandidate>> candidateSupplier,
            Supplier<T> createAction);
}
