package com.mannschaft.app.team.service;

import com.mannschaft.app.organization.TeamApplicationGroupMode;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * チーム加盟の書き込みが、組織ドメインの情報（組織の状態・可視性・チームグループ）を引くための窓口（ポート）。
 *
 * <p>team ドメインは organization ドメインの Repository / Entity を直接参照できない
 * （モジュラーモノリスの境界。{@code CrossDomainRepositoryDependencyArchTest} /
 * {@code CrossDomainEntityImportArchTest}）。加盟の書き込みは「チーム行 → 組織行」の固定順ロックを
 * <b>同じトランザクション</b>で取る必要がある（F01.2.1 §6.1・§6.9）ため、組織側の読み書きは
 * 本インターフェース越しに行い、実装は organization ドメインが持つ
 * （{@code OrganizationAffiliationPortAdapter}）。越境トランザクションになる理由は、組織行のロックと
 * チーム行のロックを分けると、アーカイブ・並行申請との直列化が成立しないためである。</p>
 *
 * <p>戻り値は Entity ではなく、必要な項目だけを持つ record にする。</p>
 */
public interface TeamAffiliationOrganizationPort {

    /**
     * slug で組織を引き、閲覧者から<b>見える</b>ときだけ組織 ID を返す。
     *
     * <p>存在しない・論理削除済み・承諾前（PROVISIONED）・アーカイブ済み・閲覧者から見えない非公開組織は、
     * すべて空を返す（呼び出し側は同じ 404 に畳む。存在オラクルを作らない）。</p>
     *
     * @param slug         組織の slug
     * @param viewerUserId 閲覧者（操作者）の userId
     */
    Optional<Long> findVisibleOrganizationId(String slug, Long viewerUserId);

    /**
     * 組織行を {@code PESSIMISTIC_WRITE}（{@code SELECT ... FOR UPDATE}）でロックし、その時点の状態を返す。
     *
     * <p>進行中のトランザクションが必須（無ければ例外）。組織が論理削除済み・不在なら 404（{@code ORG_001}）。
     * アーカイブ済みかどうかは {@link OrganizationAffiliationState#archived()} で返し、応答の選択は呼び出し側が行う。</p>
     */
    OrganizationAffiliationState lockForAffiliation(Long organizationId);

    /**
     * 指定のチームグループが、その組織の<b>生存</b>グループかを返す（他組織のグループ・削除済みは false）。
     */
    boolean isAliveGroupOfOrganization(Long organizationId, UUID groupId);

    /**
     * 組織の表示用の最小情報を ID 集合でまとめて引く（SQL は1本。不在・削除済みは含めない）。
     */
    Map<Long, OrganizationRef> findOrganizationRefs(Collection<Long> organizationIds);

    /**
     * 生存しているチームグループの表示名を ID 集合でまとめて引く（SQL は1本。削除済みは含めない）。
     *
     * <p>戻り値の {@link GroupRef#organizationId()} で、呼び出し側が「行の組織のグループであること」を確かめる
     * （テナントを跨いだ名前の混入を防ぐ）。</p>
     */
    Map<UUID, GroupRef> findAliveGroupRefs(Collection<UUID> groupIds);

    /**
     * 組織の現在の状態を<b>ロックせずに</b>読む（F01.2.1 2-C）。組織ドメインの読み取りトランザクションで完結し、
     * 呼び出し側のトランザクションには参加させない（チームの書き込みトランザクションの外で呼ぶ）。
     *
     * <p>不在・論理削除済み・承諾前（PROVISIONED）は空を返す。アーカイブ済みかどうかは
     * {@link OrganizationAffiliationState#archived()} で返し、応答の選択は呼び出し側が行う。</p>
     */
    Optional<OrganizationAffiliationState> findAffiliationState(Long organizationId);

    /**
     * ロック時点の組織の状態。
     *
     * @param id                 組織 ID
     * @param slug               組織の slug（通知の遷移先 URL に使う）
     * @param name               組織名（通知の文面に使う）
     * @param archived           アーカイブ済みか
     * @param applicationEnabled チームからの加盟申請を受け付けているか
     * @param groupsEnabled      チームグループ機能が有効か
     * @param groupMode          申請時のグループ選択の<b>保存値</b>（実効値は {@link #effectiveGroupMode()}）
     */
    record OrganizationAffiliationState(Long id, String slug, String name, boolean archived,
                                        boolean applicationEnabled, boolean groupsEnabled,
                                        TeamApplicationGroupMode groupMode) {

        /** 実効モード（§5.5）: グループ機能が off のときは保存値にかかわらず OFF として扱う。 */
        public TeamApplicationGroupMode effectiveGroupMode() {
            return groupsEnabled ? groupMode : TeamApplicationGroupMode.OFF;
        }
    }

    /** 組織の表示用の最小情報。 */
    record OrganizationRef(Long id, String slug, String name, String iconUrl, boolean groupsEnabled) {
    }

    /** チームグループの表示用の最小情報。 */
    record GroupRef(UUID id, Long organizationId, String name) {
    }
}
