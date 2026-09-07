package com.mannschaft.app.common.duplicatename;

import java.util.List;

/**
 * CMP-260901-1538 柱③-A: 同名確認フローの HMAC fingerprint 発行・検証。
 *
 * <p>fingerprint はサーバ秘密鍵で署名し、以下すべてに束縛する:</p>
 * <ul>
 *   <li>スコープ種別（{@link DuplicateNameScopeKind}） — 組織向け fingerprint をチーム作成へ流用不可にする</li>
 *   <li>正規化済み名称（trim 済み） — 別名称への流用を防ぐ</li>
 *   <li>操作者ユーザーID — 他ユーザーの確認結果の横流しを防ぐ</li>
 *   <li>候補 ID 集合 — 確認時に提示した候補と、作成 TX 内で再計算した候補が完全一致することを保証する</li>
 *   <li>TTL（有効期限） — 長期間の使い回しを防ぐ</li>
 * </ul>
 */
public interface DuplicateNameFingerprintService {

    /**
     * fingerprint を発行する。
     *
     * @param scopeKind       スコープ種別
     * @param normalizedName  正規化済み名称（trim 済み）
     * @param actorUserId     操作者ユーザーID
     * @param candidateIds    確認時に提示した候補 ID 集合
     * @return 発行済み fingerprint（TTL・署名込み）
     */
    String issue(DuplicateNameScopeKind scopeKind, String normalizedName, Long actorUserId, List<String> candidateIds);

    /**
     * fingerprint を検証する。
     *
     * <p>署名不一致・TTL 超過・スコープ/名称/操作者/候補集合のいずれかの不一致で {@code false}。</p>
     *
     * @param fingerprint     クライアントから返送された fingerprint
     * @param scopeKind       検証時点のスコープ種別
     * @param normalizedName  検証時点の正規化済み名称
     * @param actorUserId     検証時点の操作者ユーザーID
     * @param candidateIds    作成 TX 内で再計算した候補 ID 集合
     * @return すべて一致し TTL 内であれば true
     */
    boolean verify(String fingerprint, DuplicateNameScopeKind scopeKind, String normalizedName,
            Long actorUserId, List<String> candidateIds);
}
