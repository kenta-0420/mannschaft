package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

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
        return userRepository.saveAndFlush(user);
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
}
