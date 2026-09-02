package com.mannschaft.app.provisioning.service;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.provisioning.dto.ProvisioningInvitationAcceptResponse;
import com.mannschaft.app.provisioning.dto.ProvisioningInvitationPreviewResponse;
import com.mannschaft.app.provisioning.repository.ProvisioningInvitationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 柱②-2: 販促プロビジョニング招待の下見（preview）/ 承諾（accept）サービス。
 *
 * <p>正本: .claude/campaigns/2026-09-01-org-governance.md 柱②。
 * 承諾は要ログイン・verified email と invite_email の一致（NFC 正規化 + lowercase・
 * {@link ProvisioningEmailNormalizer}）を必須とし、二重承諾防止は
 * {@link ProvisioningInvitationRepository#findByTokenHashForUpdate} の悲観ロックで担保する。
 * 同一 TX 内で ADMIN role + membership 付与 → {@code activate()} → status=ACCEPTED → 監査 →
 * 通知 outbox まで完結させる（途中失敗時は AC12 のとおり全ロールバックする）。</p>
 *
 * <p>本 PR では試練（受け入れテスト）のみを設置する（骨格は
 * {@link UnsupportedOperationException} を投げる）。実装は後続 PR（出陣）で行う。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ProvisioningAcceptanceService {

    private final ProvisioningInvitationRepository invitationRepository;
    private final SecretTokenVault secretTokenVault;
    private final ProvisioningEmailNormalizer emailNormalizer;

    /**
     * ログインユーザーの検証済みメールアドレス（{@code status=ACTIVE} = 認証メール確認済み）の照合に使う。
     * 越境読み取りは email 1 属性の単純な参照に限定する（role→user の表示名参照と同様の軽量参照）。
     */
    private final UserRepository userRepository;

    /**
     * トークンの下見（承諾前確認画面用）。存在しない/期限切れ/取消済みは一律 PROV_001（404）。
     *
     * @param tokenPlaintext 平文トークン（POST ボディで受け取ったもの）
     * @return 下見応答
     */
    public ProvisioningInvitationPreviewResponse preview(String tokenPlaintext) {
        // TODO 出陣で実装:
        //  1. secretTokenVault.hash(tokenPlaintext) で findByTokenHash
        //  2. 不在/期限切れ(AC7)/CANCELLED・EXPIRED(AC8) は PROV_001（404）へ一律で畳む
        //  3. 対象スコープ名を解決して返す（要ログイン。未ログインは SecurityConfig が401で弾く）
        throw new UnsupportedOperationException("ProvisioningAcceptanceService#preview is not implemented yet");
    }

    /**
     * トークンを承諾する。ADMIN role + membership 付与 → activate() → ACCEPTED → 監査 → 通知 outbox
     * を同一 TX で行う。
     *
     * @param tokenPlaintext 平文トークン
     * @param actorUserId    実行ユーザー ID（要ログイン）
     * @return 承諾結果
     */
    @Transactional
    public ProvisioningInvitationAcceptResponse accept(String tokenPlaintext, Long actorUserId) {
        // TODO 出陣で実装:
        //  1. secretTokenVault.hash(tokenPlaintext) → invitationRepository.findByTokenHashForUpdate
        //     （悲観ロック。同一トークンへの並行承諾は片方のみ成功・AC6）
        //  2. 不在は PROV_001（404）
        //  3. status==ACCEPTED かつ acceptedBy==actorUserId は本人限定の冪等成功応答（AC9）。
        //     status==ACCEPTED かつ acceptedBy!=actorUserId は PROV_010（404・別ユーザーには畳む）
        //  4. status!=PENDING（CANCELLED/EXPIRED）は PROV_003（409）
        //  5. expiresAt が過去なら PROV_002（409。境界=ちょうどは有効・AC7）
        //  6. userRepository.findById(actorUserId) で取得した email を
        //     emailNormalizer.normalize(inviteEmail) と emailNormalizer.normalize(ログインユーザーの
        //     検証済みメール) で比較し、不一致なら PROV_006（403・AC4）
        //  7. 一致すれば ADMIN role + membership 付与 → 対象 team/organization の activate() →
        //     招待 status=ACCEPTED・acceptedAt/acceptedBy/resolvedAt 設定 → 監査記録(AC15) →
        //     通知 outbox 投入。途中で例外が起きれば同一 TX でロールバックし、
        //     スコープは PROVISIONED・招待は PENDING のまま残る（AC12）。
        throw new UnsupportedOperationException("ProvisioningAcceptanceService#accept is not implemented yet");
    }
}
