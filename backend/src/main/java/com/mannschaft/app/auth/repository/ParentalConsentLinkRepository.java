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
     * Release バッチ用: 指定ステータスかつ<b>子ユーザーが成人に到達している可能性がある</b>
     * リンクを、{@code id} 昇順のキーセットページングで取得する。
     *
     * <h2>なぜ SQL では生年（{@code birth_year}）でしか絞れないのか</h2>
     * <p>{@code users.birth_date} は {@code EncryptedStringConverter} により AES-256-GCM で
     * 暗号化されて {@code VARBINARY} に格納されている。IV がランダムであるため同じ生年月日でも
     * 暗号文は毎回異なり、SQL 上の比較は<b>暗号文同士のバイト比較</b>にしかならず日付順とは
     * 無関係である。したがって {@code birth_date} を {@code WHERE} 句の範囲条件に使ってはならない。
     * 平文・索引付きで比較可能なのは {@code users.birth_year}（{@code SMALLINT}）のみである。</p>
     *
     * <h2>粗い絞り込みと確定判定の二段構え</h2>
     * <p>本クエリは {@code birthYear <= :maxBirthYear}（{@code maxBirthYear} = 今日の年 − 18）で
     * <b>粗く</b>絞る。この年に生まれた者は「今年18歳になる」ため、まだ誕生日前の未成年が混ざる。
     * それより古い年の者は全員確実に成人である。すなわち本クエリは
     * <b>成人を取りこぼさない（偽陰性ゼロ）</b>ことだけを保証する候補抽出であり、
     * 未成年を確実に除外することは保証しない。境界年の確定判定は呼び出し側が
     * 復号済み {@code birthDate} を {@code AgeGroupCalculator} に掛けて行うこと。</p>
     *
     * <h2>{@code birth_year} が NULL の行を候補に含める理由</h2>
     * <p>{@code birth_year} は F09.17 の広告セグメント用に後付けされた列で、登録経路での
     * 書き込みが長らく無く、既存行はすべて NULL である。ここで {@code birth_year IS NOT NULL} と
     * 書くと本バッチは 1 件も拾えない無処理バッチに堕ち、成人到達者の同意が永久に解放されない。
     * よって NULL 行は候補に含め、呼び出し側の復号判定に委ねる（安全側）。
     * {@code birth_year} が全行埋まれば、本条件はそのまま索引による絞り込みとして効き始める。</p>
     *
     * <h2>キーセットページング（飢餓の構造的排除）</h2>
     * <p>{@code l.id > :cursor} で前ページの最終 {@code id} から続きを取る。呼び出し側は
     * 判定結果にかかわらずカーソルを検査済みの最後の {@code id} まで前進させること。
     * 先頭ページを毎回取り直すオフセットページングに戻すと、先頭ページが境界年の未成年で
     * 埋まった場合に後方の成人到達者へ永久に到達できず、保護者同意が解放されないまま残る
     * （未成年保護の法的要件に直結する）。</p>
     *
     * @param status       絞り込むステータス（通常 APPROVED）
     * @param maxBirthYear 候補に含める生年の上限（この年を含む）。通常「今日の年 − 18」
     * @param cursor       直前ページの最終 {@code id}（初回は {@code 00000000-0000-0000-0000-000000000000}）
     * @param pageable     ページング設定（サイズのみ使用。ページ番号は常に 0）
     * @return 対象の同意リンクのリスト（id 昇順）
     */
    @Query("SELECT l FROM ParentalConsentLinkEntity l "
            + "WHERE l.status = :status AND l.id > :cursor AND EXISTS ("
            + "  SELECT 1 FROM UserEntity u "
            + "  WHERE u.id = l.childUserId AND u.deletedAt IS NULL "
            + "    AND (u.birthYear IS NULL OR u.birthYear <= :maxBirthYear)"
            + ") ORDER BY l.id ASC")
    List<ParentalConsentLinkEntity> findAdultCandidateLinksAfterId(
            @Param("status") ParentalConsentLinkStatus status,
            @Param("maxBirthYear") int maxBirthYear,
            @Param("cursor") UUID cursor,
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

    /**
     * 子ユーザー・保護者ユーザーの組み合わせで、<b>ステータスを問わず</b>同意リンクが
     * 存在したことがあるかを確認する（PENDING/APPROVED/REJECTED/REVOKED のいずれでも true）。
     *
     * <p>{@code GuardianshipSwitchService#endSwitch}（後見切替終了）の認可で使用する。
     * 行は {@link ParentalConsentLinkEntity#revoke} 等でステータスを書き換えるのみで
     * 物理削除されないため、本メソッドは「過去に一度でも当該 (child, parent) の組で
     * リンクが作成されたか」を判定する。切替中にリンクが解除された正当な保護者を
     * 締め出さないための<b>意図的に緩い</b>存在チェックである
     * （認可根治戦役 Wave5・endSwitch 是正）。</p>
     *
     * @param childUserId  子ユーザーの ID
     * @param parentUserId 保護者ユーザーの ID
     * @return いずれかのステータスでリンクが存在したことがある場合 true
     */
    boolean existsByChildUserIdAndParentUserId(Long childUserId, Long parentUserId);
}
