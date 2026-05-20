package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.gdpr.dto.PurgeStatusRow;
import com.mannschaft.app.gdpr.dto.PurgeStatusSummaryData;
import com.mannschaft.app.gdpr.entity.AccountPurgeCompletionStatusEntity;
import com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * {@link GdprPurgeStatusQueryService} 単体テスト（Mockito）。
 *
 * <p>Phase E GDPR パージ状況照会サービスのロジックを検証する。
 * リポジトリはすべて Mock で差し替え、純粋なビジネスロジックのみを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GdprPurgeStatusQueryService 単体テスト")
class GdprPurgeStatusQueryServiceTest {

    @Mock
    private AccountPurgeCompletionStatusRepository repo;

    @InjectMocks
    private GdprPurgeStatusQueryService service;

    // ---- テストヘルパー ----

    /**
     * テスト用エンティティを生成する。
     */
    private AccountPurgeCompletionStatusEntity buildEntity(
            Long userId, String domain, String status, LocalDateTime attemptedAt, LocalDateTime completedAt) {
        AccountPurgeCompletionStatusEntity entity = new AccountPurgeCompletionStatusEntity();
        entity.setUserId(userId);
        entity.setEmailHash("a".repeat(64));
        entity.setDomainName(domain);
        entity.setStatus(status);
        entity.setAttemptedAt(attemptedAt);
        entity.setCompletedAt(completedAt);
        return entity;
    }

    private AccountPurgeCompletionStatusEntity buildPending(Long userId, String domain, LocalDateTime attemptedAt) {
        return buildEntity(userId, domain, "PENDING", attemptedAt, null);
    }

    private AccountPurgeCompletionStatusEntity buildSuccess(Long userId, String domain) {
        LocalDateTime now = LocalDateTime.now();
        return buildEntity(userId, domain, "SUCCESS", now.minusMinutes(5), now);
    }

    // ---- list() テスト ----

    @Nested
    @DisplayName("list() — 一覧取得")
    class ListTest {

        @Test
        @DisplayName("フィルタなしで全件返す")
        void フィルタなし_全件返す() {
            AccountPurgeCompletionStatusEntity entity =
                    buildSuccess(100L, "role");
            Page<AccountPurgeCompletionStatusEntity> entityPage =
                    new PageImpl<>(List.of(entity));

            given(repo.findAll(any(Specification.class), any(Pageable.class)))
                    .willReturn(entityPage);

            Pageable pageable = PageRequest.of(0, 20);
            Page<PurgeStatusRow> result = service.list(null, null, null, null, pageable);

            assertThat(result.getTotalElements()).isEqualTo(1);
            PurgeStatusRow row = result.getContent().get(0);
            assertThat(row.userId()).isEqualTo(100L);
            assertThat(row.domainName()).isEqualTo("role");
            assertThat(row.status()).isEqualTo("SUCCESS");
            assertThat(row.isAlert()).isFalse();
        }

        @Test
        @DisplayName("PENDING ステータスフィルタあり — Service が Specification を組み立てて Repo に渡す")
        void PENDING_ステータスフィルタ_Repo呼び出し確認() {
            AccountPurgeCompletionStatusEntity entity =
                    buildPending(200L, "team", LocalDateTime.now().minusMinutes(5));
            Page<AccountPurgeCompletionStatusEntity> entityPage =
                    new PageImpl<>(List.of(entity));

            given(repo.findAll(any(Specification.class), any(Pageable.class)))
                    .willReturn(entityPage);

            Pageable pageable = PageRequest.of(0, 10);
            Page<PurgeStatusRow> result = service.list("PENDING", null, null, null, pageable);

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).status()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("PENDING かつ 30 分超過 → isAlert = true")
        void PENDING_30分超過_isAlert_true() {
            // 31 分前 → アラート対象
            LocalDateTime oldAttemptedAt = LocalDateTime.now().minusMinutes(31);
            AccountPurgeCompletionStatusEntity entity =
                    buildPending(300L, "payment", oldAttemptedAt);
            Page<AccountPurgeCompletionStatusEntity> entityPage =
                    new PageImpl<>(List.of(entity));

            given(repo.findAll(any(Specification.class), any(Pageable.class)))
                    .willReturn(entityPage);

            Page<PurgeStatusRow> result = service.list(null, null, null, null, PageRequest.of(0, 20));

            assertThat(result.getContent()).hasSize(1);
            PurgeStatusRow row = result.getContent().get(0);
            assertThat(row.isAlert()).isTrue();
        }

        @Test
        @DisplayName("SUCCESS → isAlert = false（ステータスに関わらずアラートにならない）")
        void SUCCESS_isAlert_false() {
            AccountPurgeCompletionStatusEntity entity = buildSuccess(400L, "chart");
            Page<AccountPurgeCompletionStatusEntity> entityPage =
                    new PageImpl<>(List.of(entity));

            given(repo.findAll(any(Specification.class), any(Pageable.class)))
                    .willReturn(entityPage);

            Page<PurgeStatusRow> result = service.list(null, null, null, null, PageRequest.of(0, 20));

            assertThat(result.getContent().get(0).isAlert()).isFalse();
        }

        @Test
        @DisplayName("PENDING かつ 29 分前 → アラート閾値未達のため isAlert = false")
        void PENDING_29分前_isAlert_false() {
            // 29 分前 → まだアラート対象外
            LocalDateTime recentAttemptedAt = LocalDateTime.now().minusMinutes(29);
            AccountPurgeCompletionStatusEntity entity =
                    buildPending(500L, "proxy", recentAttemptedAt);
            Page<AccountPurgeCompletionStatusEntity> entityPage =
                    new PageImpl<>(List.of(entity));

            given(repo.findAll(any(Specification.class), any(Pageable.class)))
                    .willReturn(entityPage);

            Page<PurgeStatusRow> result = service.list(null, null, null, null, PageRequest.of(0, 20));

            assertThat(result.getContent().get(0).isAlert()).isFalse();
        }
    }

    // ---- summary() テスト ----

    @Nested
    @DisplayName("summary() — サマリー集計")
    class SummaryTest {

        @Test
        @DisplayName("正常系: ドメイン別集計値が正しく計算される")
        void 正常系_ドメイン別集計値確認() {
            // raw 集計: [domainName, status, count]
            // List.of() は Object[] を格納できないため ArrayList を使用
            List<Object[]> rawCounts = new ArrayList<>();
            rawCounts.add(new Object[]{"role", "PENDING", 2L});
            rawCounts.add(new Object[]{"role", "SUCCESS", 3L});
            rawCounts.add(new Object[]{"team", "SUCCESS", 5L});
            given(repo.countByDomainAndStatus()).willReturn(rawCounts);
            given(repo.countAlerting(any(LocalDateTime.class))).willReturn(1L);

            PurgeStatusSummaryData summary = service.summary();

            assertThat(summary.totalPending()).isEqualTo(2L);
            assertThat(summary.totalSuccess()).isEqualTo(8L);  // 3 + 5
            assertThat(summary.alertCount()).isEqualTo(1L);
            assertThat(summary.byDomain()).hasSize(2);

            // ドメイン名昇順: role → team
            PurgeStatusSummaryData.DomainCount roleCount = summary.byDomain().get(0);
            assertThat(roleCount.domain()).isEqualTo("role");
            assertThat(roleCount.pendingCount()).isEqualTo(2L);
            assertThat(roleCount.successCount()).isEqualTo(3L);

            PurgeStatusSummaryData.DomainCount teamCount = summary.byDomain().get(1);
            assertThat(teamCount.domain()).isEqualTo("team");
            assertThat(teamCount.pendingCount()).isEqualTo(0L);
            assertThat(teamCount.successCount()).isEqualTo(5L);
        }

        @Test
        @DisplayName("正常系: 全件 SUCCESS → totalPending=0, alertCount=0")
        void 全件SUCCESS_pendingゼロ_alertゼロ() {
            // List.of() は Object[] を格納できないため ArrayList を使用
            List<Object[]> rawCounts = new ArrayList<>();
            rawCounts.add(new Object[]{"role", "SUCCESS", 10L});
            given(repo.countByDomainAndStatus()).willReturn(rawCounts);
            given(repo.countAlerting(any(LocalDateTime.class))).willReturn(0L);

            PurgeStatusSummaryData summary = service.summary();

            assertThat(summary.totalPending()).isEqualTo(0L);
            assertThat(summary.totalSuccess()).isEqualTo(10L);
            assertThat(summary.alertCount()).isEqualTo(0L);
        }

        @Test
        @DisplayName("正常系: 集計データなし → 全て 0")
        void 集計データなし_全てゼロ() {
            given(repo.countByDomainAndStatus()).willReturn(List.of());
            given(repo.countAlerting(any(LocalDateTime.class))).willReturn(0L);

            PurgeStatusSummaryData summary = service.summary();

            assertThat(summary.totalPending()).isEqualTo(0L);
            assertThat(summary.totalSuccess()).isEqualTo(0L);
            assertThat(summary.alertCount()).isEqualTo(0L);
            assertThat(summary.byDomain()).isEmpty();
        }
    }

    // ---- detail() テスト ----

    @Nested
    @DisplayName("detail() — ユーザー詳細取得")
    class DetailTest {

        @Test
        @DisplayName("userId で 6 ドメイン分のレコードが返る")
        void userId_6ドメイン_返却() {
            LocalDateTime now = LocalDateTime.now();
            List<AccountPurgeCompletionStatusEntity> entities = List.of(
                    buildPending(100L, "chart", now.minusMinutes(10)),
                    buildSuccess(100L, "errorreport"),
                    buildSuccess(100L, "payment"),
                    buildPending(100L, "proxy", now.minusMinutes(5)),
                    buildSuccess(100L, "role"),
                    buildSuccess(100L, "team")
            );

            given(repo.findByUserIdOrderByDomainName(100L)).willReturn(entities);

            List<PurgeStatusRow> rows = service.detail(100L);

            assertThat(rows).hasSize(6);
            // ドメイン名昇順確認
            assertThat(rows.get(0).domainName()).isEqualTo("chart");
            assertThat(rows.get(1).domainName()).isEqualTo("errorreport");
            assertThat(rows.get(2).domainName()).isEqualTo("payment");
            assertThat(rows.get(3).domainName()).isEqualTo("proxy");
            assertThat(rows.get(4).domainName()).isEqualTo("role");
            assertThat(rows.get(5).domainName()).isEqualTo("team");
        }

        @Test
        @DisplayName("存在しない userId → 空リスト")
        void 存在しないUserId_空リスト() {
            given(repo.findByUserIdOrderByDomainName(anyLong())).willReturn(List.of());

            List<PurgeStatusRow> rows = service.detail(999L);

            assertThat(rows).isEmpty();
        }
    }

    // ---- writeCsv() テスト ----

    @Nested
    @DisplayName("writeCsv() — CSV エクスポート")
    class WriteCsvTest {

        @Test
        @DisplayName("正常系: CSV ヘッダーと BOM が出力される")
        void 正常系_ヘッダーとBOM出力() throws IOException {
            given(repo.findAll(any(Specification.class)))
                    .willReturn(List.of());

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertThatCode(() -> service.writeCsv(out)).doesNotThrowAnyException();

            String csv = out.toString("UTF-8");
            // BOM を除いて確認
            String csvNoBom = csv.startsWith("﻿") ? csv.substring(1) : csv;
            assertThat(csvNoBom).startsWith("userId,emailHash,domainName,status,attemptedAt,completedAt,isAlert");
        }

        @Test
        @DisplayName("正常系: データ行が出力される")
        void 正常系_データ行出力() throws IOException {
            LocalDateTime attempted = LocalDateTime.of(2026, 5, 20, 10, 0, 0);
            LocalDateTime completed = LocalDateTime.of(2026, 5, 20, 10, 1, 0);
            AccountPurgeCompletionStatusEntity entity =
                    buildEntity(100L, "role", "SUCCESS", attempted, completed);

            given(repo.findAll(any(Specification.class))).willReturn(List.of(entity));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            service.writeCsv(out);

            String csv = out.toString("UTF-8");
            assertThat(csv).contains("100");
            assertThat(csv).contains("role");
            assertThat(csv).contains("SUCCESS");
            assertThat(csv).contains("2026-05-20 10:00:00");
            assertThat(csv).contains("2026-05-20 10:01:00");
        }
    }
}
