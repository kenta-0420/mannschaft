package com.mannschaft.app.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * F22.1 市（Market）統一決済 R1: 手数料パターンマスタ（{@code fee_policies}）のリポジトリ。
 *
 * <p>主キーは自然キー {@code policy_key}（String）。全テナント共通のマスタゆえ
 * {@code AbstractTenantAwareRepository}（organization_id 絞り込み）は実装しない（設計書 01 §3.6・原則7 例外）。</p>
 */
@Repository
public interface FeePolicyRepository extends JpaRepository<FeePolicyEntity, String> {

    /**
     * 自然キー（policy_key）でパターンを取得する。
     *
     * @param policyKey 自然キー
     * @return パターン（無ければ empty）
     */
    Optional<FeePolicyEntity> findByPolicyKey(String policyKey);

    /**
     * 有効（enabled=TRUE）なパターンのみ自然キーで取得する。解決時はこちらを用い、無効パターンを除外する。
     *
     * @param policyKey 自然キー
     * @return 有効パターン（無効/不在なら empty）
     */
    Optional<FeePolicyEntity> findByPolicyKeyAndEnabledTrue(String policyKey);

    /**
     * 有効なパターン一覧を取得する（管理画面・解決候補表示用）。
     *
     * @return 有効パターン一覧
     */
    List<FeePolicyEntity> findByEnabledTrue();

    /**
     * 全パターン（{@code enabled=false} 含む）を policy_key 昇順で取得する（シスアド管理一覧用）。
     *
     * @return 全パターン一覧（policy_key 昇順）
     */
    List<FeePolicyEntity> findAllByOrderByPolicyKeyAsc();
}
