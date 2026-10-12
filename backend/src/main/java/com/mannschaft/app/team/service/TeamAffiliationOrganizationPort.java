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
     * 組織の状態を、組織行の<b>共有ロック</b>（{@code SELECT ... FOR SHARE}）を取って読み直す（F01.2.1 2-D・§6.9）。
     *
     * <p>招待の作成は組織行をロックしないため、コミットの後に組織の状態を読み直す。ロックしない読み取りでは、
     * 並行する組織のアーカイブ（組織行の {@code PESSIMISTIC_WRITE}）がまだコミットされていないと見逃し、
     * そのアーカイブの片付け（{@code OrganizationArchivedEvent}）との前後が時間の偶然に左右される。
     * 共有ロックで読めば、アーカイブがロックを持っている間は待ち、コミット後の状態（アーカイブ済み）を見る。
     * 組織ドメインの短いトランザクションで閉じ、呼び出し側のトランザクションには参加させない
     * （チームの書き込みトランザクションの外で呼ぶ）。</p>
     *
     * <p>不在・論理削除済み・承諾前（PROVISIONED）は空を返す。</p>
     */
    Optional<OrganizationAffiliationState> recheckAffiliationStateLocked(Long organizationId);

    /**
     * slug で組織の表示用の最小情報を引く（可視性で絞らない。F01.2.1 2-D の離脱）。
     *
     * <p>離脱は、加盟している組織であれば非公開・アーカイブ済みでも行えるため、可視性では絞らない。
     * 存在しない slug・論理削除済み・承諾前（PROVISIONED）は空（呼び出し側は「加盟していない」と同じ 404 に畳む）。</p>
     */
    Optional<OrganizationRef> findOrganizationRefBySlug(String slug);

    /**
     * 論理削除済みを含めて、組織の<b>いまコミットされている</b>閉鎖の状態（アーカイブ・論理削除）と表示名を返す
     * （F01.2.1 2-D・加盟の片付け）。行が無いときだけ空を返す。
     *
     * <p>組織ドメインの<b>新しい</b>読み取りトランザクション（{@code REQUIRES_NEW}）で読み、呼び出し側（team ドメインの片付け）の
     * トランザクションには参加しない。片付けは、消す加盟行を先に {@code FOR UPDATE} でロックしてから本メソッドで最新の状態を
     * 確かめる（ロックした行は、確かめた時点のアーカイブより前に作られた行に限られるので、解除後の新しい申請を消さない）。
     * 論理削除済みでも組織名を返すので、取消の通知の文面を省かずに済む。</p>
     */
    Optional<OrganizationClosureState> findClosureStateIncludingDeleted(Long organizationId);

    /**
     * 指定の組織のうち、アーカイブ済み・論理削除済み・行の無いものの ID を返す（ロックしない。F01.2.1 2-D・片付けの回復バッチの
     * 候補選び）。最終的な判定は片付けの中で {@link #findClosureStateIncludingDeleted} により行う。
     */
    java.util.Set<Long> findClosedOrganizationIds(Collection<Long> organizationIds);

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

    /**
     * 組織の閉鎖の状態（論理削除済みを含む）。
     *
     * @param id       組織 ID
     * @param slug     組織の slug
     * @param name     組織名（取消の通知の文面に使う。論理削除済みでも引ける）
     * @param archived アーカイブ済みか
     * @param deleted  論理削除済みか
     */
    record OrganizationClosureState(Long id, String slug, String name, boolean archived, boolean deleted) {

        /** 申請・招待を受け付けない状態（アーカイブ済みか論理削除済み）か。 */
        public boolean closed() {
            return archived || deleted;
        }
    }

    /** 組織の表示用の最小情報。 */
    record OrganizationRef(Long id, String slug, String name, String iconUrl, boolean groupsEnabled) {
    }

    /** チームグループの表示用の最小情報。 */
    record GroupRef(UUID id, Long organizationId, String name) {
    }
}
