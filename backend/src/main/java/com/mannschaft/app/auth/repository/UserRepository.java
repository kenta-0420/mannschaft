package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.entity.UserEntity.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * ユーザーリポジトリ。
 */
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByEmail(String email);

    /** @ハンドルでユーザーを取得する（F04.8 連絡先機能）。 */
    Optional<UserEntity> findByContactHandle(String contactHandle);

    /** @ハンドルの使用有無を確認する（自分以外）。 */
    boolean existsByContactHandleAndIdNot(String contactHandle, Long excludeId);

    /** @ハンドルの使用有無を確認する（全ユーザー）。 */
    boolean existsByContactHandle(String contactHandle);

    List<UserEntity> findByStatusAndCreatedAtBefore(UserStatus status, LocalDateTime threshold);

    /**
     * ユーザーIDコレクションから一括取得する（N+1 防止）。
     *
     * <p>F03.12 §14 主催者点呼候補者一覧取得時に、表示名・アバターを 1 クエリで解決するために使用する。</p>
     *
     * @param ids ユーザーID コレクション
     * @return 該当ユーザー一覧（@SQLRestriction により未削除のみ）
     */
    List<UserEntity> findByIdIn(Collection<Long> ids);

    /**
     * F20.3 Phase2 自動付与バッチの走査対象＝活性ユーザーIDをページング列挙する（設計書 F20.3 03 §6）。
     *
     * <p><b>JPQL で scalar（{@code Long}）だけを射影する</b>: 呼び出し元（{@code billing.beta} の
     * {@code BetaPerkAutoGrantBatchService}）は billing ドメインだが、他ドメイン Entity（{@link UserEntity}）を
     * 受け取らず ID スカラのみを扱うため、クロスドメイン Entity 参照番人（D-1）に抵触しない
     * （{@code LoginActivityQueryService} が {@code AuditLogRepository} を scalar 参照するのと同型）。</p>
     *
     * <p><b>native SQL を使わない理由</b>: {@link org.hibernate.annotations.SQLRestriction @SQLRestriction}
     * （{@code deleted_at IS NULL}）は JPQL/HQL にのみ効き、native SQL は貫通してしまう。native だと退会申請直後に
     * 弱匿名化で {@code deleted_at} を立てた（＝退会撤回ウィンドウ中の）ユーザーまで拾ってしまうため、必ず JPQL で
     * {@code status = ACTIVE AND deleted_at IS NULL} を明示評価する（{@code deleted_at IS NULL} は @SQLRestriction と
     * 二重だが意図を明示するため冪等に併記）。安定ページングのため {@code id} 昇順で並べる。</p>
     *
     * @param pageable ページング（ソートは {@code id} 昇順を渡す）
     * @return 活性ユーザーの id ページ
     */
    @Query("SELECT u.id FROM UserEntity u "
            + "WHERE u.status = com.mannschaft.app.auth.entity.UserEntity.UserStatus.ACTIVE "
            + "AND u.deletedAt IS NULL")
    Page<Long> findActiveUserIdsForBeta(Pageable pageable);

    boolean existsByEmail(String email);

    /**
     * メールアドレスの使用有無を確認する（論理削除済みユーザーを含む）。
     * @SQLRestriction をバイパスするためネイティブSQL使用。退会処理中の保持期間中は再登録を防ぐ。
     */
    @Query(value = "SELECT COUNT(*) > 0 FROM users WHERE email = :email", nativeQuery = true)
    boolean existsByEmailIncludingDeleted(@Param("email") String email);

    /**
     * メールアドレスでユーザーを取得する（論理削除済みユーザーを含む）。
     * @SQLRestriction をバイパスするためネイティブSQL使用。退会取り消しログイン時に使用する。
     */
    @Query(value = "SELECT * FROM users WHERE email = :email LIMIT 1", nativeQuery = true)
    Optional<UserEntity> findByEmailIncludingDeleted(@Param("email") String email);

    /**
     * 横断検索（グローバル検索）用の利用者検索。閲覧者と同一スコープに在籍する利用者に限定する。
     *
     * <p>絞り込みは 3 条件の AND とする:</p>
     * <ol>
     *   <li>{@code visibleUserIds} — 閲覧者が所属するチーム／組織に在籍する利用者のみを候補とする
     *       （所属を共有しない相手は横断検索に出さない）</li>
     *   <li>{@code isSearchable = true} — 利用者本人が設定した検索許可フラグを尊重する
     *       （退会時の匿名化でも false になるため、匿名化済みアカウントも除外される）</li>
     *   <li>{@code status = ACTIVE} — 停止中・保留中のアカウントは候補にしない</li>
     * </ol>
     *
     * <p>検索述語は {@code displayName} のみとする。{@code email} を述語に含めると
     * 「そのメールアドレスが登録済みか」を照会できてしまい、表示名検索という本来の用途にも不要なため
     * 除外する。論理削除済みは {@code UserEntity} の {@code @SQLRestriction} が除外する。</p>
     *
     * <p>呼び出し側は {@code visibleUserIds} が空の場合、{@code IN ()} の発行を避けるため
     * ダミー値（{@code -1L}）で埋めること。</p>
     *
     * @param keyword        検索キーワード（表示名の部分一致）
     * @param visibleUserIds 閲覧者と所属を共有する利用者 ID 集合（非空・空ならダミー値）
     * @param pageable       取得件数
     * @return 同一スコープ内の検索結果
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT u FROM UserEntity u
            WHERE u.displayName LIKE %:keyword%
              AND u.id IN :visibleUserIds
              AND u.isSearchable = true
              AND u.status = com.mannschaft.app.auth.entity.UserEntity.UserStatus.ACTIVE
            """)
    java.util.List<UserEntity> searchByKeyword(
            @org.springframework.data.repository.query.Param("keyword") String keyword,
            @org.springframework.data.repository.query.Param("visibleUserIds") java.util.Collection<Long> visibleUserIds,
            org.springframework.data.domain.Pageable pageable);

    long countByStatus(UserEntity.UserStatus status);

    /**
     * F01.9 保護者同意ゲート: ユーザーの現在ステータスのみを軽量取得する。
     *
     * <p>{@link com.mannschaft.app.auth.service.StatusClaimResolver} が JWT の {@code ppc}
     * クレーム（PENDING_PARENTAL_CONSENT 判定）を解決する際に使用する。エンティティ全体を
     * ロードせず status 列のみを射影することで、トークン発行・更新の全経路で軽量に判定できる。</p>
     *
     * @param userId 対象ユーザー ID
     * @return ステータス（未削除ユーザーのみ。存在しない場合は空）
     */
    @Query("SELECT u.status FROM UserEntity u WHERE u.id = :userId")
    Optional<UserStatus> findStatusById(@Param("userId") Long userId);

    long countByLastLoginAtAfterAndStatusAndDeletedAtIsNull(LocalDateTime since, UserEntity.UserStatus status);

    // === Analytics 集計用クエリ ===

    /**
     * 指定日に作成されたユーザー数を取得する。
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(u) FROM UserEntity u WHERE CAST(u.createdAt AS localdate) = :date")
    int countNewUsersByDate(@org.springframework.data.repository.query.Param("date") java.time.LocalDate date);

    /**
     * 指定日時点のアクティブユーザー数を取得する（ACTIVE かつ未削除）。
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(u) FROM UserEntity u WHERE u.status = 'ACTIVE' AND u.deletedAt IS NULL " +
            "AND u.createdAt <= :endOfDay")
    int countActiveUsersAsOf(@org.springframework.data.repository.query.Param("endOfDay") LocalDateTime endOfDay);

    /**
     * 指定日時点の全ユーザー数（未削除）を取得する。
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(u) FROM UserEntity u WHERE u.deletedAt IS NULL " +
            "AND u.createdAt <= :endOfDay")
    int countTotalUsersAsOf(@org.springframework.data.repository.query.Param("endOfDay") LocalDateTime endOfDay);

    /**
     * 指定月に登録されたユーザーのIDリストを取得する（コホート用）。
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT u.id FROM UserEntity u WHERE CAST(u.createdAt AS localdate) BETWEEN :monthStart AND :monthEnd")
    java.util.List<Long> findUserIdsCreatedBetween(
            @org.springframework.data.repository.query.Param("monthStart") java.time.LocalDate monthStart,
            @org.springframework.data.repository.query.Param("monthEnd") java.time.LocalDate monthEnd);

    /**
     * 指定ユーザーIDリストのうちアクティブ（ACTIVE かつ未削除）なユーザー数を取得する。
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(u) FROM UserEntity u WHERE u.id IN :userIds " +
            "AND u.status = 'ACTIVE' AND u.deletedAt IS NULL")
    int countActiveByUserIds(@org.springframework.data.repository.query.Param("userIds") java.util.List<Long> userIds);

    /**
     * userId に対応する locale 文字列のみを取得する（UserLocaleFilter 用軽量クエリ）。
     */
    @org.springframework.data.jpa.repository.Query("SELECT u.locale FROM UserEntity u WHERE u.id = :userId AND u.deletedAt IS NULL")
    Optional<String> findLocaleById(@org.springframework.data.repository.query.Param("userId") Long userId);

    /**
     * userId に対応する timezone 文字列のみを取得する（F09.17 フリークエンシーキャップ
     * の週境界をユーザーローカル時刻で評価するために使用する）。
     *
     * @param userId ユーザーID
     * @return timezone 文字列（例: "Asia/Tokyo"）。ユーザー未存在・論理削除済みなら empty。
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT u.timezone FROM UserEntity u WHERE u.id = :userId AND u.deletedAt IS NULL")
    Optional<String> findTimezoneById(@org.springframework.data.repository.query.Param("userId") Long userId);

    /**
     * 物理削除対象ユーザーを取得する。
     * @SQLRestriction("deleted_at IS NULL") をバイパスするためネイティブSQLを使用。
     */
    @Query(value = """
        SELECT * FROM users
        WHERE deleted_at < :cutoff
          AND purged_at IS NULL
          AND id != 0
        ORDER BY deleted_at ASC
        """, nativeQuery = true)
    List<UserEntity> findPurgeTargets(
            @Param("cutoff") LocalDateTime cutoff,
            Pageable pageable);

    /**
     * 退会済み（deleted_atが指定範囲内）かつ未purgeのユーザーを取得する。
     * @SQLRestriction をバイパスするためネイティブSQL使用。
     */
    @Query(value = """
        SELECT * FROM users
        WHERE deleted_at >= :from
          AND deleted_at < :to
          AND purged_at IS NULL
        ORDER BY deleted_at ASC
        """, nativeQuery = true)
    List<UserEntity> findPendingDeletionUsers(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /** メンバー一覧表示用の最小プロジェクション */
    interface MemberSummary {
        Long getId();
        String getDisplayName();
        String getAvatarUrl();
    }

    /**
     * メンバー一覧用に displayName・avatarUrl のみを取得する。
     * 暗号化フィールド（lastName/firstName 等）を含むフルエンティティをロードしないことで、
     * seed データの平文カラム値による EncryptionService 復号エラーを回避する。
     */
    @Query(value = "SELECT u.id, u.display_name AS displayName, u.avatar_url AS avatarUrl FROM users u WHERE u.id = :id AND u.deleted_at IS NULL", nativeQuery = true)
    Optional<MemberSummary> findMemberSummaryById(@Param("id") Long id);

    // === 広告ターゲティング セグメント検索クエリ（F09.17 AdSegmentEvaluator Phase B）===

    /**
     * GENDER セグメント: gender_hash が指定リストに含まれるアクティブユーザーIDを取得する。
     *
     * <p>gender は AES-256-GCM 暗号化済みのため直接比較不可。
     * HMAC-SHA256 のブラインドインデックス（gender_hash）を使用する。</p>
     *
     * @param hashes HMAC-SHA256 ハッシュのリスト（EncryptionService.hmac() で生成）
     * @return 一致したユーザーID リスト
     */
    @Query("SELECT u.id FROM UserEntity u WHERE u.genderHash IN :hashes AND u.deletedAt IS NULL")
    List<Long> findUserIdsByGenderHashIn(@Param("hashes") List<String> hashes);

    /**
     * REGION_PREFECTURE セグメント: prefecture_code_hash が指定リストに含まれるアクティブユーザーIDを取得する。
     *
     * <p>prefecture_code は AES-256-GCM 暗号化済みのため直接比較不可。
     * HMAC-SHA256 のブラインドインデックス（prefecture_code_hash）を使用する。</p>
     *
     * @param hashes HMAC-SHA256 ハッシュのリスト（EncryptionService.hmac() で生成）
     * @return 一致したユーザーID リスト
     */
    @Query("SELECT u.id FROM UserEntity u WHERE u.prefectureCodeHash IN :hashes AND u.deletedAt IS NULL")
    List<Long> findUserIdsByPrefectureCodeHashIn(@Param("hashes") List<String> hashes);

    /**
     * REGION_CITY セグメント: city_code_hash が指定リストに含まれるアクティブユーザーIDを取得する。
     *
     * <p>city_code は AES-256-GCM 暗号化済みのため直接比較不可。
     * HMAC-SHA256 のブラインドインデックス（city_code_hash）を使用する。</p>
     *
     * @param hashes HMAC-SHA256 ハッシュのリスト（EncryptionService.hmac() で生成）
     * @return 一致したユーザーID リスト
     */
    @Query("SELECT u.id FROM UserEntity u WHERE u.cityCodeHash IN :hashes AND u.deletedAt IS NULL")
    List<Long> findUserIdsByCityCodeHashIn(@Param("hashes") List<String> hashes);

    /**
     * AGE_RANGE セグメント: birth_year が指定範囲（minBirthYear 以上 maxBirthYear 以下）のアクティブユーザーIDを取得する。
     *
     * <p>birth_date は AES-256-GCM 暗号化のため索引不可。
     * 平文で保持する birth_year カラム（V68.004 追加）を使用して範囲検索を行う。
     * 年齢 age から生年 birthYear への変換は呼び出し側で行うこと（例: 現在年 - age）。</p>
     *
     * @param minBirthYear 対象年齢範囲の最大生年（年齢の若い側: 例 age=39 なら currentYear-39）
     * @param maxBirthYear 対象年齢範囲の最小生年（年齢の高い側: 例 age=20 なら currentYear-20）
     * @return 一致したユーザーID リスト
     */
    @Query("SELECT u.id FROM UserEntity u WHERE u.birthYear BETWEEN :minBirthYear AND :maxBirthYear AND u.deletedAt IS NULL")
    List<Long> findUserIdsByBirthYearBetween(@Param("minBirthYear") int minBirthYear,
                                             @Param("maxBirthYear") int maxBirthYear);
}
