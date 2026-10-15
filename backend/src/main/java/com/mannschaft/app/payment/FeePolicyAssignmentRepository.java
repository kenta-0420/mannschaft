package com.mannschaft.app.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F22.1 市（Market）統一決済 R1: 手数料パターン割当（{@code fee_policy_assignments}）のリポジトリ。
 *
 * <p>解決は derived query で行い、{@code @Query}（JPQL）を用いない（同ドメイン・組み立て注記の散在防止）。
 * 全テナント共通の運用データゆえ {@code AbstractTenantAwareRepository} は実装しない（設計書 01 §3.7・原則7 例外）。</p>
 */
@Repository
public interface FeePolicyAssignmentRepository extends JpaRepository<FeePolicyAssignmentEntity, UUID> {

    /**
     * {@code (source_kind, sub_key)} 完全一致の有効割当（論理削除を除外）を取得する（解決順序 ①・設計書 02 §3.5.1）。
     *
     * @param sourceKind 解決キー
     * @param subKey     細分キー（非 null）
     * @return 完全一致のアクティブ割当（無ければ empty）
     */
    Optional<FeePolicyAssignmentEntity> findBySourceKindAndSubKeyAndEnabledTrueAndDeletedAtIsNull(
            String sourceKind, String subKey);

    /**
     * {@code (source_kind, sub_key IS NULL)} の source_kind 既定の有効割当（論理削除を除外）を取得する
     * （解決順序 ②・設計書 02 §3.5.1）。
     *
     * @param sourceKind 解決キー
     * @return source_kind 既定のアクティブ割当（無ければ empty）
     */
    Optional<FeePolicyAssignmentEntity> findBySourceKindAndSubKeyIsNullAndEnabledTrueAndDeletedAtIsNull(
            String sourceKind);

    /**
     * 指定 source_kind の有効割当一覧（論理削除を除外）を取得する（管理画面・確認用）。
     *
     * @param sourceKind 解決キー
     * @return アクティブ割当一覧
     */
    List<FeePolicyAssignmentEntity> findBySourceKindAndEnabledTrueAndDeletedAtIsNull(String sourceKind);

    /**
     * 論理削除されていない全割当を取得する（シスアド管理画面の一覧・enabled=false も含む全件）。
     *
     * @return 未削除の全割当（作成順）
     */
    List<FeePolicyAssignmentEntity> findByDeletedAtIsNullOrderByCreatedAtAsc();

    /**
     * {@code (source_kind, sub_key)} の割当を <b>論理削除も含めて</b>取得する（sub_key 非 null・R2 では organization_id は NULL 固定）。
     *
     * <p>DB の UNIQUE 制約 {@code uk_fpa_target(source_kind, sub_key, organization_id)} は {@code deleted_at} を
     * 加味しない（MySQL は filtered unique 非対応・DDL コメント参照）。重複検知・論理削除済み行の復活判定に用い、
     * INSERT による UNIQUE 例外（症状を隠した 500）を未然に防ぐ。</p>
     *
     * @param sourceKind 解決キー
     * @param subKey     細分キー（非 null）
     * @return 割当（論理削除含む。無ければ empty）
     */
    Optional<FeePolicyAssignmentEntity> findBySourceKindAndSubKey(String sourceKind, String subKey);

    /**
     * {@code (source_kind, sub_key IS NULL)} の source_kind 既定割当を <b>論理削除も含めて</b>取得する
     * （R2 では organization_id は NULL 固定）。重複検知・論理削除済み行の復活判定に用いる。
     *
     * @param sourceKind 解決キー
     * @return 既定割当（論理削除含む。無ければ empty）
     */
    Optional<FeePolicyAssignmentEntity> findBySourceKindAndSubKeyIsNull(String sourceKind);

    /**
     * 指定 policy_key を参照する未削除割当の件数を数える（管理一覧の「割当数」表示用）。
     *
     * @param policyKey 手数料パターンの自然キー
     * @return 未削除割当の件数
     */
    long countByPolicyKeyAndDeletedAtIsNull(String policyKey);
}
