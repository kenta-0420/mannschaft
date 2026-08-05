package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.entity.ParentalConsentLinkEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F01.9 年齢確認・保護者同意機能: 保護者同意リンクリポジトリ。
 *
 * <p>parental_consent_links テーブルへのアクセスを提供する。
 * PK は {@link UUID}（UUIDv7 / BINARY(16)）。</p>
 */
public interface ParentalConsentLinkRepository extends JpaRepository<ParentalConsentLinkEntity, UUID> {

    /**
     * 子ユーザーに紐付くすべての同意リンクを取得する。
     *
     * @param childUserId 子ユーザーの ID
     * @return 同意リンクのリスト（空の場合は空リスト）
     */
    List<ParentalConsentLinkEntity> findByChildUserId(Long childUserId);

    /**
     * 子ユーザーとステータスを指定して同意リンクを取得する。
     *
     * @param childUserId 子ユーザーの ID
     * @param status      絞り込むステータス
     * @return 一致する同意リンクのリスト
     */
    List<ParentalConsentLinkEntity> findByChildUserIdAndStatus(
            Long childUserId, ParentalConsentLinkStatus status);

    /**
     * 保護者ユーザーとステータスを指定して同意リンクを取得する。
     * 保護者がシステム登録済みユーザーである場合に利用する。
     *
     * @param parentUserId 保護者のユーザー ID
     * @param status       絞り込むステータス
     * @return 一致する同意リンクのリスト
     */
    List<ParentalConsentLinkEntity> findByParentUserIdAndStatus(
            Long parentUserId, ParentalConsentLinkStatus status);

    /**
     * トークンハッシュで同意リンクを検索する。
     * メール内のリンクを保護者がクリックした際の照合に使用する。
     *
     * @param tokenHash SHA-256 ハッシュ値（16進文字列）
     * @return 一致する同意リンク（存在しない場合は空 Optional）
     */
    Optional<ParentalConsentLinkEntity> findByTokenHash(String tokenHash);

    /**
     * 子ユーザーとステータスで同意リンク数をカウントする。
     * PENDING 件数の上限チェックなどに利用する。
     *
     * @param childUserId 子ユーザーの ID
     * @param status      絞り込むステータス
     * @return 件数
     */
    long countByChildUserIdAndStatus(Long childUserId, ParentalConsentLinkStatus status);

    /**
     * 同一の子ユーザー・保護者メール・ステータスの組み合わせが存在するか確認する。
     * 重複申請の防止に利用する。
     *
     * @param childUserId  子ユーザーの ID
     * @param parentEmail  保護者のメールアドレス
     * @param status       絞り込むステータス
     * @return 存在する場合 true
     */
    boolean existsByChildUserIdAndParentEmailAndStatus(
            Long childUserId, String parentEmail, ParentalConsentLinkStatus status);

    /**
     * 子ユーザー・保護者ユーザー・ステータスを指定して同意リンクの存在を確認する。
     * F08.9 代理払い認可（GUARDIAN 経路）で「払い手が受益者の承認済み保護者か」を
     * boolean で判定するために利用する（payment ドメインは {@code ParentalConsentService}
     * 経由でのみ呼び出す・Entity を直接参照しない）。
     *
     * @param childUserId  子（受益者）ユーザーの ID
     * @param parentUserId 保護者（払い手）ユーザーの ID
     * @param status       絞り込むステータス（通常 APPROVED）
     * @return 一致するリンクが存在する場合 true
     */
    boolean existsByChildUserIdAndParentUserIdAndStatus(
            Long childUserId, Long parentUserId, ParentalConsentLinkStatus status);

    /**
     * バッチ用: 指定ステータスかつ指定日時より前に期限切れになった同意リンクを取得する。
     * 主に PENDING リンクの期限切れ処理（自動 REVOKE など）で使用する。
     *
     * @param status    絞り込むステータス（通常 PENDING）
     * @param threshold この日時より前に期限切れになったリンクを対象とする
     * @return 対象の同意リンクのリスト
     */
    List<ParentalConsentLinkEntity> findByStatusAndExpiresAtBefore(
            ParentalConsentLinkStatus status, LocalDateTime threshold);

    /**
     * GDPR 削除用: 子ユーザーに紐付く同意リンクをすべて物理削除する。
     * AccountPurgeService から呼び出される。
     *
     * @param childUserId 削除対象の子ユーザー ID
     */
    void deleteByChildUserId(Long childUserId);

    /**
     * Release バッチ用: 指定ステータスかつ<b>子ユーザーが既に成人に到達している</b>
     * リンクのみをページングで取得する。
     *
     * <p>年齢条件は必ず本クエリの {@code WHERE} 句で絞り込む。取得後にアプリ側で
     * 未成年を読み飛ばす実装にすると、取得上限（ページサイズ）を未成年が占有した場合に
     * 成人到達者が永久に取得されず、保護者同意が解放されないまま残る。これは未成年保護の
     * 法的要件に直結する不具合であり、事後フィルタに戻してはならない。</p>
     *
     * <p>{@code birth_date} は {@code YYYY-MM-DD} 形式の文字列カラムであり、辞書順比較が
     * 日付順比較と一致する。境界は閉区間（{@code <=}）とし、誕生日当日に18歳へ到達した
     * 子ユーザーを当日中に解放する。{@code adultBirthDateThreshold} は
     * {@code AgeGroupCalculator.adultBirthDateThreshold(today)} が唯一の算出元である
     * （年齢判定を二重実装しない）。</p>
     *
     * @param status                  絞り込むステータス（通常 APPROVED）
     * @param adultBirthDateThreshold 成人と判定される生年月日の上限（この日を含む）
     * @param pageable                ページング設定
     * @return 対象の同意リンクのリスト（id 昇順）
     */
    @Query("SELECT l FROM ParentalConsentLinkEntity l "
            + "WHERE l.status = :status AND EXISTS ("
            + "  SELECT 1 FROM UserEntity u "
            + "  WHERE u.id = l.childUserId AND u.deletedAt IS NULL "
            + "    AND u.birthDate IS NOT NULL "
            + "    AND u.birthDate <= :adultBirthDateThreshold"
            + ") ORDER BY l.id ASC")
    List<ParentalConsentLinkEntity> findAdultApprovedLinks(
            @Param("status") ParentalConsentLinkStatus status,
            @Param("adultBirthDateThreshold") String adultBirthDateThreshold,
            Pageable pageable);

    /**
     * バッチ用: 指定ステータスのリンクを id 昇順で安定ページング取得する。
     *
     * <p>F08.9 P3c-3 自立移行通知バッチ（進学予告）で、全 APPROVED 保護者リンク
     * （保護者→子）を横断的に列挙するために使用する。ページ間で順序が安定するよう
     * id 昇順で取得し、{@code pageNumber} を進めて全件走査する。</p>
     *
     * @param status   絞り込むステータス（通常 APPROVED）
     * @param pageable ページング設定
     * @return 対象の同意リンクのリスト（id 昇順）
     */
    List<ParentalConsentLinkEntity> findByStatusOrderByIdAsc(
            ParentalConsentLinkStatus status, Pageable pageable);

    /**
     * Cleanup バッチ用: 指定ステータス群のいずれかに該当するリンクが子ユーザーに存在するか確認する。
     * 期限切れ PENDING 失効後に子アカウントを削除するかどうかの判定に使用する。
     *
     * @param childUserId 子ユーザーの ID
     * @param statuses    絞り込むステータスのコレクション（例: [APPROVED] / [PENDING]）
     * @return いずれかのステータスに一致するリンクが存在する場合 true
     */
    boolean existsByChildUserIdAndStatusIn(Long childUserId, Collection<ParentalConsentLinkStatus> statuses);
}
