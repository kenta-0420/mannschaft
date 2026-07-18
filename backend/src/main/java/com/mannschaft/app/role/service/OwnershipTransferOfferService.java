package com.mannschaft.app.role.service;

import com.mannschaft.app.role.dto.TransferOwnershipAcceptResponse;
import com.mannschaft.app.role.dto.TransferOwnershipOfferCreateRequest;
import com.mannschaft.app.role.dto.TransferOwnershipOfferResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * オーナー委譲 承諾型オファーサービス（F01.2・骨格）。
 *
 * <p><strong>骨格（スタブ）:</strong> 本クラスのメソッドは未実装であり
 * {@link UnsupportedOperationException} を投げる。実装は /出陣 で行う。</p>
 *
 * <p>通常委譲（承諾型 accept）と退会 purge 経由の強制委譲は取り違え防止のため別メソッドに分離する
 * （設計書 H-2）。accept は既存 {@code RoleService#transferOwnership} の「薄いラッパ」であり
 * 無改修流用ではない（引数組み替え・2FA チェック追加・エラー再マッピング。設計書 H-3）。</p>
 *
 * <p>設計書: docs/features/F01.2_org_team_member_role/03_business_logic.md
 * 「オーナー委譲 承諾フロー（2ステップ・承諾型）」。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OwnershipTransferOfferService {

    /**
     * オーナー委譲を打診する（PENDING オファーを作成。ロールは変わらない）。
     *
     * @param scopeId     スコープ（チーム/組織）ID
     * @param scopeType   スコープ種別（{@code TEAM} / {@code ORGANIZATION}）
     * @param request     打診リクエスト（targetUserId）
     * @param actorUserId 実行ユーザー ID（対象スコープの ADMIN）
     * @return 作成されたオファー
     */
    @Transactional
    public TransferOwnershipOfferResponse createOffer(
            Long scopeId, String scopeType,
            TransferOwnershipOfferCreateRequest request, Long actorUserId) {
        throw new UnsupportedOperationException("未実装: /出陣で実装");
    }

    /**
     * オファーを承諾する（＝委譲を実行。対象→ADMIN 昇格・発行者→MEMBER 降格）。
     *
     * <p>指名相手本人のみ承諾可（宛先照合 = IDOR 防止）。承諾者の 2FA 設定を検証してから
     * 既存 {@code RoleService#transferOwnership} を薄いラッパ層で呼ぶ（設計書 H-3）。</p>
     *
     * @param scopeId     スコープ ID
     * @param scopeType   スコープ種別
     * @param offerId     オファー ID
     * @param actorUserId 実行ユーザー ID（指名相手本人）
     * @return 委譲結果（新 ADMIN / 旧 ADMIN）
     */
    @Transactional
    public TransferOwnershipAcceptResponse acceptOffer(
            Long scopeId, String scopeType, UUID offerId, Long actorUserId) {
        throw new UnsupportedOperationException("未実装: /出陣で実装");
    }

    /**
     * オファーを辞退する（{@code status=DECLINED}。ロール不変）。指名相手本人のみ。
     *
     * @param scopeId     スコープ ID
     * @param scopeType   スコープ種別
     * @param offerId     オファー ID
     * @param actorUserId 実行ユーザー ID（指名相手本人）
     */
    @Transactional
    public void declineOffer(Long scopeId, String scopeType, UUID offerId, Long actorUserId) {
        throw new UnsupportedOperationException("未実装: /出陣で実装");
    }

    /**
     * オファーを取消す（{@code status=CANCELLED}。ロール不変）。発行者または対象スコープ ADMIN のみ。
     *
     * @param scopeId     スコープ ID
     * @param scopeType   スコープ種別
     * @param offerId     オファー ID
     * @param actorUserId 実行ユーザー ID（発行者 or ADMIN）
     */
    @Transactional
    public void cancelOffer(Long scopeId, String scopeType, UUID offerId, Long actorUserId) {
        throw new UnsupportedOperationException("未実装: /出陣で実装");
    }

    /**
     * 退会（アカウント purge）に伴う最後の ADMIN 承継のための強制委譲（承諾スキップ・2FA チェックなし）。
     *
     * <p>通常の承諾型 accept とは別経路（設計書 H-2・GDPR 30 日タイムリミット順守）。
     * purge 経路（{@code AccountPurgeService} / {@code RolePurgeEventListener}）から同期即時で呼ぶ。
     * 監査に {@code forced=true} を明示する。</p>
     *
     * @param scopeId        スコープ ID
     * @param scopeType      スコープ種別
     * @param issuerUserId   承継元（退会する現 ADMIN）ユーザー ID
     * @param targetUserId   承継先ユーザー ID
     */
    @Transactional
    public void forceTransferForPurge(
            Long scopeId, String scopeType, Long issuerUserId, Long targetUserId) {
        throw new UnsupportedOperationException("未実装: /出陣で実装");
    }
}
