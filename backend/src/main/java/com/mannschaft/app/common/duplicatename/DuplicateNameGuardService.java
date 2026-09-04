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
 *       作成 TX 内で候補集合を再計算し fingerprint を再検証してから作成を許可する。
 *       確認後に新たな同名候補が出現していれば（fingerprint 不一致）再度 409</li>
 * </ol>
 */
public interface DuplicateNameGuardService {

    /**
     * 作成前チェック。同名候補があり確認未完了なら例外を投げる。呼び出し元は本メソッドが
     * 正常返却した場合のみ作成処理を続行してよい（作成 TX 内で呼ぶことで再判定を兼ねる）。
     *
     * @param scopeKind         スコープ種別（組織/チーム）
     * @param rawName           作成しようとしている名称（trim 前）
     * @param actorUserId       操作者ユーザーID
     * @param confirmDuplicate  クライアントが同名確認済みとして送信したか
     * @param suppliedFingerprint クライアントが返送した fingerprint（confirmDuplicate=false 時は無視）
     * @param candidateSupplier 同名候補を検索するコールバック（trim + utf8mb4_0900_ai_ci 相当の
     *                          {@code =} 比較で検索し、可視性ルール適用済みの
     *                          {@link DuplicateNameCandidate} 一覧を返す想定）。作成 TX 内で
     *                          呼ばれるため、常に最新状態を反映する
     */
    void checkForCreate(DuplicateNameScopeKind scopeKind, String rawName, Long actorUserId,
            boolean confirmDuplicate, String suppliedFingerprint,
            Supplier<List<DuplicateNameCandidate>> candidateSupplier);
}
