package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.entity.ParentalConsentLinkEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.util.AgeGroupCalculator;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F01.9 18歳到達保護者同意自動解放バッチの<b>実 DB</b> 結合テスト。
 *
 * <p>本テストが必要な理由: 取得条件をリポジトリのモックで代替すると、SQL が実際に何を返すかを
 * 一切検証できない。{@code users.birth_date} は {@code EncryptedStringConverter} により
 * AES-256-GCM（ランダム IV）で暗号化されて格納されており、SQL 上の比較は暗号文同士のバイト比較に
 * しかならず日付順とは無関係である。にもかかわらずモックの上では「日付で絞れているつもり」の
 * テストがすべて緑になってしまう。よって年齢による絞り込みは必ず実 DB（Testcontainers MySQL）
 * 上で検証する。</p>
 *
 * <p>あわせて、平文・索引付きの {@code birth_year} による粗い絞り込みと、復号済み生年月日による
 * 確定判定の二段構えが、境界年の未成年を解放せず、かつ成人到達者を飢餓させないことを検証する。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("18歳到達保護者同意自動解放バッチ 実DB結合テスト")
class ParentalConsentReleaseBatchIntegrationTest extends AbstractMySqlIntegrationTest {

    /** 本番実装と同じページサイズ。*/
    private static final int PAGE_SIZE = 500;

    /** 本番実装と同じ日付基準タイムゾーン。*/
    private static final ZoneId BATCH_ZONE = ZoneId.of("Asia/Tokyo");

    @Autowired
    private ParentalConsentLinkRepository linkRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ParentalConsentReleaseBatchService releaseBatchService;

    /** メールアドレス・トークンハッシュの一意性を確保するための連番。*/
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    private static LocalDate today() {
        return LocalDate.now(BATCH_ZONE);
    }

    // -------------------------------------------------------------------
    // フィクスチャ
    // -------------------------------------------------------------------

    /**
     * 子ユーザーを 1 人保存する。
     *
     * @param birthDate 生年月日（暗号化列 {@code birth_date} に格納される）
     * @param birthYear 平文の生年（{@code null} を渡すと未設定の既存行を再現する）
     * @param deleted   論理削除済みにするか
     * @return 保存済みユーザー
     */
    private UserEntity saveChild(LocalDate birthDate, Integer birthYear, boolean deleted) {
        long n = SEQ.incrementAndGet();
        UserEntity user = UserEntity.builder()
                .email("child" + n + "@example.com")
                .lastName("山田")
                .firstName("太郎")
                .displayName("child" + n)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .isSearchable(true)
                .birthDate(birthDate.toString())
                .birthYear(birthYear)
                .deletedAt(deleted ? LocalDateTime.now() : null)
                .build();
        return userRepository.saveAndFlush(user);
    }

    /** 子ユーザーに APPROVED リンクを 1 本張る。*/
    private ParentalConsentLinkEntity saveApprovedLink(UserEntity child) {
        ParentalConsentLinkEntity link = ParentalConsentLinkEntity.builder()
                .childUserId(child.getId())
                .parentEmail("parent@example.com")
                .tokenHash("hash-" + SEQ.incrementAndGet())
                .status(ParentalConsentLinkStatus.APPROVED)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
        return linkRepository.saveAndFlush(link);
    }

    /** 生年月日と生年を揃えて子ユーザー＋APPROVED リンクを用意する。*/
    private ParentalConsentLinkEntity givenApprovedChild(LocalDate birthDate) {
        return saveApprovedLink(saveChild(birthDate, birthDate.getYear(), false));
    }

    /** バッチと同じ条件で候補を取得する。*/
    private List<ParentalConsentLinkEntity> fetchCandidates(int limit) {
        return linkRepository.findAdultCandidateLinksAfterId(
                ParentalConsentLinkStatus.APPROVED,
                AgeGroupCalculator.adultBirthDateThreshold(today()).getYear(),
                new UUID(0L, 0L),
                PageRequest.of(0, limit));
    }

    // -------------------------------------------------------------------
    // 取得クエリ（findAdultCandidateLinksAfterId）
    // -------------------------------------------------------------------

    @Test
    @DisplayName("確実に成人の生年（十分に古い）の子は候補として取得される")
    void 古い生年の子は候補に含まれる() {
        ParentalConsentLinkEntity link = givenApprovedChild(LocalDate.of(1980, 1, 1));

        assertThat(fetchCandidates(PAGE_SIZE))
                .extracting(ParentalConsentLinkEntity::getId)
                .contains(link.getId());
    }

    @Test
    @DisplayName("論理削除済みユーザーのリンクは候補から除外される")
    void 論理削除済みユーザーは候補から除外される() {
        UserEntity deletedChild = saveChild(LocalDate.of(1980, 1, 1), 1980, true);
        ParentalConsentLinkEntity link = saveApprovedLink(deletedChild);

        assertThat(fetchCandidates(PAGE_SIZE))
                .extracting(ParentalConsentLinkEntity::getId)
                .doesNotContain(link.getId());
    }

    @Test
    @DisplayName("生年が確実に未成年の範囲の子は候補から除外される（索引による粗い絞り込みが効いている）")
    void 明らかに未成年の生年は候補から除外される() {
        // 今年生まれ＝どう転んでも未成年。birth_year による絞り込みが本当に効いていれば返らない。
        ParentalConsentLinkEntity link = givenApprovedChild(today().withMonth(1).withDayOfMonth(1));

        assertThat(fetchCandidates(PAGE_SIZE))
                .extracting(ParentalConsentLinkEntity::getId)
                .doesNotContain(link.getId());
    }

    @Test
    @DisplayName("生年が未設定（NULL）の子も候補に残る（無処理バッチに堕ちないための安全側）")
    void 生年未設定の子も候補に残る() {
        // birth_year は後付けの列で既存行は NULL。ここで除外すると成人到達者が永久に解放されない。
        UserEntity child = saveChild(LocalDate.of(1980, 1, 1), null, false);
        ParentalConsentLinkEntity link = saveApprovedLink(child);

        assertThat(fetchCandidates(PAGE_SIZE))
                .extracting(ParentalConsentLinkEntity::getId)
                .contains(link.getId());
    }

    @Test
    @DisplayName("カーソルより後ろのリンクだけが返る（キーセットページングが実 DB で成立する）")
    void カーソルより後ろだけが返る() {
        givenApprovedChild(LocalDate.of(1980, 1, 1));
        givenApprovedChild(LocalDate.of(1981, 1, 1));

        List<ParentalConsentLinkEntity> firstPage = linkRepository.findAdultCandidateLinksAfterId(
                ParentalConsentLinkStatus.APPROVED,
                AgeGroupCalculator.adultBirthDateThreshold(today()).getYear(),
                new UUID(0L, 0L), PageRequest.of(0, 1));
        assertThat(firstPage).hasSize(1);

        List<ParentalConsentLinkEntity> secondPage = linkRepository.findAdultCandidateLinksAfterId(
                ParentalConsentLinkStatus.APPROVED,
                AgeGroupCalculator.adultBirthDateThreshold(today()).getYear(),
                firstPage.get(0).getId(), PageRequest.of(0, PAGE_SIZE));

        // 2ページ目に1ページ目の行が再登場しないこと（＝カーソルが効いていること）。
        // UUID の大小は DB 側のバイト比較で決まるため、Java の UUID#compareTo（符号付き比較）で
        // 検証してはならない。ここでは「再登場しない」という観測可能な事実のみを検証する。
        assertThat(secondPage)
                .extracting(ParentalConsentLinkEntity::getId)
                .doesNotContain(firstPage.get(0).getId());
    }

    // -------------------------------------------------------------------
    // バッチ本体（execute）
    // -------------------------------------------------------------------

    @Test
    @DisplayName("境界年の未成年（誕生日前）のリンクは解放されない")
    void 境界年の未成年は解放されない() {
        // 明日18歳になる子。生年は成人と同じ年になり得るため SQL では除外できず、
        // 復号済み生年月日による確定判定だけが未成年を守る。
        LocalDate turnsAdultTomorrow = AgeGroupCalculator.adultBirthDateThreshold(today()).plusDays(1);
        assertThat(AgeGroupCalculator.isMinor(turnsAdultTomorrow, today()))
                .as("前提: この生年月日は本日時点で未成年であること").isTrue();
        ParentalConsentLinkEntity link = givenApprovedChild(turnsAdultTomorrow);

        releaseBatchService.execute();

        assertThat(link.getStatus()).isEqualTo(ParentalConsentLinkStatus.APPROVED);
    }

    @Test
    @DisplayName("誕生日当日に18歳へ到達した子のリンクは当日中に解放される")
    void 誕生日当日に18歳到達した子は当日中に解放される() {
        LocalDate turnsAdultToday = AgeGroupCalculator.adultBirthDateThreshold(today());
        assertThat(AgeGroupCalculator.isMinor(turnsAdultToday, today()))
                .as("前提: この生年月日は本日時点で成人であること").isFalse();
        ParentalConsentLinkEntity link = givenApprovedChild(turnsAdultToday);

        releaseBatchService.execute();

        assertThat(link.getStatus()).isEqualTo(ParentalConsentLinkStatus.REVOKED);
        assertThat(link.getRevokedBy()).as("SYSTEM による自動解放").isNull();
    }

    @Test
    @DisplayName("論理削除済みユーザーのリンクは解放されない")
    void 論理削除済みユーザーのリンクは解放されない() {
        UserEntity deletedChild = saveChild(LocalDate.of(1980, 1, 1), 1980, true);
        ParentalConsentLinkEntity link = saveApprovedLink(deletedChild);

        releaseBatchService.execute();

        assertThat(link.getStatus()).isEqualTo(ParentalConsentLinkStatus.APPROVED);
    }

    @Test
    @DisplayName("AC-0-6: 先頭ページが境界年の未成年で埋まっても後方の成人到達者は解放される（飢餓しない）")
    void 先頭ページが未成年で埋まっても成人到達者は飢餓しない() {
        // 先頭 PAGE_SIZE 件を「明日18歳になる」未成年で埋める。
        // これらは birth_year では除外できないため、候補の先頭ページを丸ごと占有する。
        LocalDate minorBirthDate = AgeGroupCalculator.adultBirthDateThreshold(today()).plusDays(1);
        List<ParentalConsentLinkEntity> minorLinks = new ArrayList<>();
        for (int i = 0; i < PAGE_SIZE; i++) {
            minorLinks.add(givenApprovedChild(minorBirthDate));
        }
        // その後方に成人到達者を置く（id 昇順で必ず未成年より後ろになる）
        List<ParentalConsentLinkEntity> adultLinks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            adultLinks.add(givenApprovedChild(LocalDate.of(1980, 5, 5)));
        }

        releaseBatchService.execute();

        assertThat(minorLinks)
                .as("境界年の未成年は解放されないこと")
                .allMatch(l -> l.getStatus() == ParentalConsentLinkStatus.APPROVED);
        assertThat(adultLinks)
                .as("未成年に埋もれた後方の成人到達者へ到達できること（飢餓しない）")
                .allMatch(l -> l.getStatus() == ParentalConsentLinkStatus.REVOKED);
    }
}
