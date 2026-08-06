package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code users.birth_year} 一回限り埋め戻しバッチの<b>実 DB</b> 結合テスト。
 *
 * <p>本テストが必要な理由: {@code users.birth_date} は
 * {@link com.mannschaft.app.common.EncryptedStringConverter} により AES-256-GCM
 * （ランダム IV）で暗号化されて格納されている。リポジトリをモックすると「復号して年を
 * 取り出せているつもり」のテストがすべて緑になり、暗号化列を経由した実際の読み書きを
 * 一切検証できない（PR #2614 で全モック化により致命欠陥を検出できなかった前例がある）。
 * よって実 DB（Testcontainers MySQL）上で検証する。</p>
 *
 * <h2>クラスレベル {@code @Transactional} を付けない理由</h2>
 * <p>本バッチの設計の中核は「オーケストレータ自体はトランザクション境界を持たず、
 * チャンクごとに独立コミットする」ことにある（{@link UserBirthYearBackfillBatchService}
 * の Javadoc 参照）。テストクラスに {@code @Transactional} を付けると、テストメソッドが
 * 開始した外側トランザクションに {@code processChunk}（デフォルト伝播 {@code REQUIRED}）が
 * 参加してしまい、チャンク単位で本当にコミットされているかを検証できなくなる
 * （テスト終了時に全チャンクがまとめてロールバックされ、中核の性質が一度も真にならない）。
 * よって本クラスは非トランザクションとし、各テストが作成した行は {@link #cleanup()} で
 * 明示的に削除することでテスト間・他テストクラスとの DB 汚染を防ぐ（{@code users} 全体を
 * 走査するバッチの性質上、コミットされた残留行はクエリに乗ってしまうため）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("users.birth_year 埋め戻しバッチ 実DB結合テスト")
class UserBirthYearBackfillBatchIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserBirthYearBackfillBatchService backfillBatchService;

    @Autowired
    private UserBirthYearBackfillChunkService chunkService;

    /** メールアドレスの一意性を確保するための連番。*/
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    /**
     * 本テストクラスが実 DB にコミットした行の id。{@link #cleanup()} で確実に削除する対象。
     * クラス非 {@code @Transactional} 化に伴い、テストが作成した行は自動ロールバックされず
     * 実コミットされるため、明示的な後始末が必須になる。
     */
    private final List<Long> createdUserIds = new ArrayList<>();

    /**
     * ユーザーを 1 人保存する。
     *
     * @param birthDate 生年月日（{@code null} なら未設定。暗号化列 {@code birth_date} に格納される）
     * @param birthYear 平文の生年（{@code null} なら未設定の既存行を再現する）
     * @return 保存済みユーザー
     */
    private UserEntity saveUser(LocalDate birthDate, Integer birthYear) {
        long n = SEQ.incrementAndGet();
        UserEntity user = UserEntity.builder()
                .email("user" + n + "@example.com")
                .lastName("山田")
                .firstName("太郎")
                .displayName("user" + n)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .isSearchable(true)
                .birthDate(birthDate != null ? birthDate.toString() : null)
                .birthYear(birthYear)
                .build();
        UserEntity saved = userRepository.saveAndFlush(user);
        createdUserIds.add(saved.getId());
        return saved;
    }

    /**
     * 本テストが実コミットした行を確実に削除する。
     *
     * <p>本バッチの候補クエリ（{@code birth_year IS NULL AND birth_date IS NOT NULL}）は
     * {@code users} テーブル全体を走査するため、削除を怠ると後続テスト（本クラス内・
     * 他クラスとも TestContext Cache により同一 MySQL コンテナ・同一テーブルを共有する）の
     * 候補集合に残留行が混入する。{@code deleteAllByIdInBatch} は {@code @SQLRestriction}
     * を経由しない実 DELETE のため、論理削除の有無に関わらず確実に消える。</p>
     */
    @AfterEach
    void cleanup() {
        if (!createdUserIds.isEmpty()) {
            userRepository.deleteAllByIdInBatch(createdUserIds);
        }
    }

    @Test
    @DisplayName("birth_year が未設定で birth_date が設定済みの行は埋め戻される")
    void 対象行は埋め戻される() {
        UserEntity user = saveUser(LocalDate.of(1990, 4, 1), null);

        backfillBatchService.execute();

        UserEntity reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getBirthYear()).isEqualTo(1990);
    }

    @Test
    @DisplayName("既に birth_year が埋まっている行は再更新されない（冪等）")
    void 既に埋まっている行は再更新されない() {
        // birth_date と矛盾する値を意図的に入れ、「再計算されない」ことを観測可能にする。
        UserEntity user = saveUser(LocalDate.of(1990, 4, 1), 1999);

        backfillBatchService.execute();

        UserEntity reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getBirthYear()).isEqualTo(1999);
    }

    @Test
    @DisplayName("birth_date が未設定（NULL）の行はスキップされ、birth_year は NULL のまま")
    void birth_dateが未設定の行はスキップされる() {
        UserEntity user = saveUser(null, null);

        backfillBatchService.execute();

        UserEntity reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getBirthYear()).isNull();
    }

    @Test
    @DisplayName("不正な birth_date を持つ行があっても他の対象行の処理は継続する")
    void 不正なbirth_dateがあっても他行の処理は継続する() {
        UserEntity brokenUser = saveUser(LocalDate.of(1990, 4, 1), null);
        // 復号は成功するがパース不能な文字列を直接書き込み、実 DB 経由の異常系を再現する。
        userRepository.saveAndFlush(brokenUser.toBuilder().birthDate("not-a-date").build());
        UserEntity healthyUser = saveUser(LocalDate.of(1985, 6, 15), null);

        backfillBatchService.execute();

        UserEntity reloadedBroken = userRepository.findById(brokenUser.getId()).orElseThrow();
        UserEntity reloadedHealthy = userRepository.findById(healthyUser.getId()).orElseThrow();
        assertThat(reloadedBroken.getBirthYear()).as("不正フォーマットはスキップされ NULL のまま").isNull();
        assertThat(reloadedHealthy.getBirthYear()).as("同一チャンク内の他行は処理が継続すること").isEqualTo(1985);
    }

    @Test
    @DisplayName("キーセットが必ず前進し、1ページ目に無い対象行も後続チャンクで取りこぼされない")
    void キーセットは前進し取りこぼされない() {
        int pageSize = UserBirthYearBackfillBatchService.PAGE_SIZE;
        List<UserEntity> firstPageUsers = new ArrayList<>();
        for (int i = 0; i < pageSize; i++) {
            firstPageUsers.add(saveUser(LocalDate.of(1970, 1, 1), null));
        }
        // 1ページ目を埋めた後方に置かれる対象行（id 昇順で必ず後段のチャンクに入る）。
        UserEntity trailingUser = saveUser(LocalDate.of(2000, 12, 31), null);

        backfillBatchService.execute();

        assertThat(firstPageUsers)
                .as("1ページ目の全行が埋め戻されること")
                .allSatisfy(u -> assertThat(
                        userRepository.findById(u.getId()).orElseThrow().getBirthYear())
                        .isEqualTo(1970));
        UserEntity reloadedTrailing = userRepository.findById(trailingUser.getId()).orElseThrow();
        assertThat(reloadedTrailing.getBirthYear())
                .as("1ページ目に収まらない後方の対象行も取りこぼされずに埋め戻されること")
                .isEqualTo(2000);
    }

    @Test
    @DisplayName("チャンク処理はカーソルを常に前進させる（キーセットページングの基礎契約）")
    void チャンク処理はカーソルを前進させる() {
        UserEntity first = saveUser(LocalDate.of(1990, 1, 1), null);
        UserEntity second = saveUser(LocalDate.of(1991, 1, 1), null);

        UserBirthYearBackfillChunkService.ChunkResult page1 = chunkService.processChunk(0L, 1);
        assertThat(page1.processedCount()).isEqualTo(1);
        assertThat(page1.newCursor()).isEqualTo(first.getId());

        UserBirthYearBackfillChunkService.ChunkResult page2 = chunkService.processChunk(page1.newCursor(), 1);
        assertThat(page2.processedCount()).isEqualTo(1);
        assertThat(page2.newCursor()).isEqualTo(second.getId());

        UserBirthYearBackfillChunkService.ChunkResult page3 = chunkService.processChunk(page2.newCursor(), 1);
        assertThat(page3.processedCount())
                .as("全対象を処理し終えたら以降は空ページを返すこと")
                .isZero();
    }

    /**
     * 本 PR の中核要件（チャンク単位の独立コミット・再開可能性）を実 DB で実証する。
     *
     * <p>1 チャンク目のみを手動処理した時点で、2 チャンク目を処理していない（＝
     * {@code execute()} の全走行が完了していない）にもかかわらず、1 チャンク目の更新が
     * <b>別スレッド（別コネクション・別トランザクション）から確定済みとして見える</b>ことを
     * 確認する。これは「オーケストレータがトランザクション境界を持たず、チャンクごとに
     * 独立コミットする」設計が実際に機能していることの証明であり、テストメソッド自体が
     * トランザクションを保持していたら（クラス {@code @Transactional} を付けていたら）
     * この検証は原理的に不可能である。</p>
     *
     * <p>続けて {@code execute()} を再実行し、1 チャンク目は冪等で再更新されず、
     * 未処理のまま残っていた 2 チャンク目が埋め戻されることを確認する。これは
     * 「途中終了しても未処理カーソルから再開できる」という耐障害性の主張の実証である
     * （本バッチは実行間でカーソルを永続化しないため、再開は「既に埋まっている行は
     * 候補クエリから除外される」という冪等性の仕組みそのものによって成立する）。</p>
     */
    @Test
    @DisplayName("チャンクは実DBに独立コミットされ、途中終了後もexecute()の再実行で未処理分から再開できる")
    void チャンクは独立コミットされ再開可能() throws InterruptedException {
        UserEntity chunk1User = saveUser(LocalDate.of(1980, 1, 1), null);
        UserEntity chunk2User = saveUser(LocalDate.of(1981, 1, 1), null);

        // 1チャンク目のみを手動処理し、2チャンク目を処理しないことで「途中終了」を模す。
        UserBirthYearBackfillChunkService.ChunkResult chunk1Result = chunkService.processChunk(0L, 1);
        assertThat(chunk1Result.processedCount()).isEqualTo(1);

        // 別スレッド（＝別コネクション・別トランザクション）から1チャンク目の更新を読む。
        // 同一スレッド内の読み取りだと「実はまだ同じトランザクション内にいるのでは」という
        // 疑いを排除できないため、明示的にスレッドを分けて検証する。
        AtomicReference<Integer> chunk1SeenFromOtherTx = new AtomicReference<>();
        Thread reader = new Thread(() ->
                chunk1SeenFromOtherTx.set(
                        userRepository.findById(chunk1User.getId()).orElseThrow().getBirthYear()));
        reader.start();
        reader.join();
        assertThat(chunk1SeenFromOtherTx.get())
                .as("2チャンク目が未処理の時点で、1チャンク目の更新が別スレッドから確定済みとして見えること"
                        + "（チャンク単位の独立コミットの証拠）")
                .isEqualTo(1980);

        UserEntity beforeResume = userRepository.findById(chunk2User.getId()).orElseThrow();
        assertThat(beforeResume.getBirthYear())
                .as("この時点で2チャンク目はまだ未処理であること")
                .isNull();

        // execute() を再実行し、未処理分（2チャンク目）から再開できることを確認する。
        backfillBatchService.execute();

        UserEntity resumedChunk1 = userRepository.findById(chunk1User.getId()).orElseThrow();
        UserEntity resumedChunk2 = userRepository.findById(chunk2User.getId()).orElseThrow();
        assertThat(resumedChunk1.getBirthYear())
                .as("既処理の1チャンク目は冪等で再更新されないこと")
                .isEqualTo(1980);
        assertThat(resumedChunk2.getBirthYear())
                .as("未処理だった2チャンク目がexecute()再実行で埋め戻されること（再開可能性の証拠）")
                .isEqualTo(1981);
    }
}
