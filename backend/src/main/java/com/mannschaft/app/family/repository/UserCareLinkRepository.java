package com.mannschaft.app.family.repository;

import com.mannschaft.app.family.CareLinkStatus;
import com.mannschaft.app.family.CareRelationship;
import com.mannschaft.app.family.entity.UserCareLinkEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * ユーザーケアリンクリポジトリ。F03.12。
 */
public interface UserCareLinkRepository extends JpaRepository<UserCareLinkEntity, Long> {

    List<UserCareLinkEntity> findByCareRecipientUserIdAndStatus(Long careRecipientUserId, CareLinkStatus status);

    List<UserCareLinkEntity> findByWatcherUserIdAndStatus(Long watcherUserId, CareLinkStatus status);

    @Query("SELECT u FROM UserCareLinkEntity u WHERE (u.careRecipientUserId = :userId OR u.watcherUserId = :userId) AND u.status = 'PENDING'")
    List<UserCareLinkEntity> findPendingInvitationsForUser(@Param("userId") Long userId);

    Optional<UserCareLinkEntity> findByInvitationToken(String token);

    boolean existsByCareRecipientUserIdAndWatcherUserId(Long careRecipientUserId, Long watcherUserId);

    boolean existsByCareRecipientUserIdAndWatcherUserIdAndStatus(
            Long careRecipientUserId, Long watcherUserId, CareLinkStatus status);

    long countByCareRecipientUserIdAndStatusIn(Long careRecipientUserId, List<CareLinkStatus> statuses);

    boolean existsByCareRecipientUserIdAndStatus(Long careRecipientUserId, CareLinkStatus status);

    /**
     * ケア対象者・見守り者・続柄・ステータスを指定してケアリンクの存在を確認する。
     *
     * <p>F08.9 代理払い認可（GUARDIAN 経路）で「払い手が受益者の見守り PARENT か」を
     * boolean 判定するために利用する（payment ドメインは {@code CareLinkService}
     * 経由でのみ呼び出す・Entity を直接参照しない）。</p>
     *
     * @param careRecipientUserId ケア対象者（受益者）のユーザーID
     * @param watcherUserId       見守り者（払い手）のユーザーID
     * @param relationship        続柄（通常 PARENT）
     * @param status              ステータス（通常 ACTIVE）
     * @return 一致するケアリンクが存在する場合 true
     */
    boolean existsByCareRecipientUserIdAndWatcherUserIdAndRelationshipAndStatus(
            Long careRecipientUserId, Long watcherUserId, CareRelationship relationship, CareLinkStatus status);

    /**
     * 見守り者・続柄・ステータスを指定してケアリンクを取得する。
     *
     * <p>F08.9 P3a 切替可能な子の列挙で「払い手が ACTIVE な見守り PARENT であるケア対象者（子）」を
     * 一覧化するために利用する（payment/auth ドメインは {@code CareLinkService} 経由でのみ呼び出す・
     * Entity を直接参照しない）。</p>
     *
     * @param watcherUserId 見守り者（保護者候補）のユーザーID
     * @param relationship  続柄（通常 PARENT）
     * @param status        ステータス（通常 ACTIVE）
     * @return 一致するケアリンク一覧
     */
    List<UserCareLinkEntity> findByWatcherUserIdAndRelationshipAndStatus(
            Long watcherUserId, CareRelationship relationship, CareLinkStatus status);

    /**
     * 複数のケア対象者ユーザーIDを IN 句で一括取得する（N+1 防止）。
     *
     * <p>F03.12 §14 主催者点呼機能。候補者一覧取得時にケアリンク情報をまとめてロードする。</p>
     *
     * @param careRecipientUserIds ケア対象者ユーザーIDのコレクション
     * @param status               取得対象ステータス（通常 ACTIVE）
     * @return 該当するケアリンク一覧
     */
    @Query("SELECT u FROM UserCareLinkEntity u WHERE u.careRecipientUserId IN :userIds AND u.status = :status")
    List<UserCareLinkEntity> findByCareRecipientUserIdInAndStatus(
            @Param("userIds") Collection<Long> careRecipientUserIds,
            @Param("status") CareLinkStatus status);

    /**
     * バッチ用: 続柄・ステータスを指定して全ケアリンクをページングで取得する。
     *
     * <p>F08.9 P3c-3 自立移行通知バッチ（進学予告）で、全 ACTIVE 見守り PARENT リンク
     * （保護者→子）を横断的に列挙するために使用する。特定の watcher / recipient に
     * 限定せず、相互の (watcher, recipient) ペアを全件走査する（id 昇順で安定ページング）。</p>
     *
     * @param relationship 続柄（通常 PARENT）
     * @param status       ステータス（通常 ACTIVE）
     * @param pageable     ページング設定（id 昇順ソート推奨）
     * @return 該当するケアリンク一覧（ページ）
     */
    List<UserCareLinkEntity> findByRelationshipAndStatusOrderByIdAsc(
            CareRelationship relationship, CareLinkStatus status, Pageable pageable);

    /**
     * ケア対象者・見守り者・続柄の組み合わせで、<b>ステータスを問わず</b>ケアリンクが
     * 存在したことがあるかを確認する（PENDING/ACTIVE/REJECTED/REVOKED のいずれでも true）。
     *
     * <p>{@code GuardianshipSwitchService#endSwitch}（後見切替終了）の認可で使用する。
     * 行は {@link com.mannschaft.app.family.entity.UserCareLinkEntity#revoke} 等で
     * ステータスを書き換えるのみで物理削除されないため、本メソッドは「過去に一度でも
     * 当該 (recipient, watcher, PARENT) の組でリンクが作成されたか」を判定する。
     * 切替中にリンクが解除された正当な保護者を締め出さないための<b>意図的に緩い</b>
     * 存在チェックである（認可根治戦役 Wave5・endSwitch 是正）。</p>
     *
     * @param careRecipientUserId ケア対象者（子）のユーザーID
     * @param watcherUserId       見守り者（保護者）のユーザーID
     * @param relationship        続柄（通常 PARENT）
     * @return いずれかのステータスでケアリンクが存在したことがある場合 true
     */
    boolean existsByCareRecipientUserIdAndWatcherUserIdAndRelationship(
            Long careRecipientUserId, Long watcherUserId, CareRelationship relationship);
}
