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
 * <h2>検分第3巡是正: TOCTOU 対策（設計判断・ロック専用テーブルの行ロック方式）</h2>
 * <p>組織名・チーム名は一意制約を持たない（同名の併存を許可する設計のため、DB レベルの
 * 一意制約で TOCTOU を機械的に防げない）。第1〜2巡では MySQL の名前付きアドバイザリロック
 * （{@code GET_LOCK}/{@code RELEASE_LOCK}）を試みたが、解放タイミング（rollback 経路での
 * 早期解放）と接続管理（Hikari 経由では {@code close()} が物理切断ではなくプール返却になる、
 * 専用接続の保持による接続プール枯渇）に構造的な問題が消えなかったため、
 * <b>{@code duplicate_name_locks} テーブルの行ロック</b>方式へ転換した。</p>
 *
 * <p>実装（{@link DuplicateNameGuardServiceImpl}）は呼び出し元と<b>同一トランザクション</b>内で
 * {@code INSERT INTO duplicate_name_locks ... ON DUPLICATE KEY UPDATE scope_kind = scope_kind}
 * を実行し、正規化名ごとに1行だけ存在するロック専用行へ X ロック（排他ロック）を取得する。
 * <b>明示的な解放処理は書かない</b>。InnoDB は commit・rollback のどちらでもそのトランザクションが
 * 保持する行ロックを自動的に解放するため、解放漏れが原理的に起こらない
 * （専用接続・{@code afterCompletion}・{@code RELEASE_LOCK} が一切不要になる）。</p>
 *
 * <p>行ロック取得後、{@code candidateSupplier} は <b>ロッキングリード（{@code FOR UPDATE}
 * 等、{@code PESSIMISTIC_WRITE} 相当）</b>で最新のコミット済みデータを読む契約とする
 * （{@code OrganizationRepository#findActiveByNormalizedNameForUpdate} 等を参照）。
 * ロック待ちが {@code innodb_lock_wait_timeout} を超えた場合は
 * {@link DuplicateNameErrorCode#DUPNAME_002}（409）へ写像する。</p>
 *
 * <p>{@code createAction}（実際の作成処理）も同一トランザクション内・行ロック保持中に
 * 実行することで、「判定 → 作成」の間に別の同名作成者が割り込めないようにする。</p>
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
     *                          {@link DuplicateNameCandidate} 一覧を返す想定）。行ロック
     *                          保持中に呼ばれるため、同名作成者間では直列化された最新状態を反映する
     * @param createAction      重複確認を通過した場合に実行する実際の作成処理（行ロック
     *                          保持中に実行される）
     * @param <T>               {@code createAction} の戻り値型
     * @return {@code createAction} の実行結果
     */
    <T> T checkForCreateAndRun(DuplicateNameScopeKind scopeKind, String rawName, Long actorUserId,
            boolean confirmDuplicate, String suppliedFingerprint,
            Supplier<List<DuplicateNameCandidate>> candidateSupplier,
            Supplier<T> createAction);
}
