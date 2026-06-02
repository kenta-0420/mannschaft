package com.mannschaft.app.cspreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.cspreport.controller.CspReportController;
import com.mannschaft.app.cspreport.dto.CspReportRequest;
import com.mannschaft.app.cspreport.entity.CspReportEntity;
import com.mannschaft.app.cspreport.repository.CspReportRepository;
import com.mannschaft.app.cspreport.service.CspReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * CSP 違反レポート受信エンドポイントのテスト。
 *
 * <p>以下のケースを検証する:</p>
 * <ol>
 *   <li>正常系: 標準 CSP レポート形式（ラッパーあり）で正常に処理される</li>
 *   <li>正常系: ラッパーなし形式でも正常に処理される</li>
 *   <li>正常系: 同じハッシュのレポートが 2 回来ると occurrence_count が増える</li>
 *   <li>異常系: 空のボディでも例外が発生しない（パース失敗は無視）</li>
 *   <li>認可: 未認証でも処理される（Service 層が呼ばれる）</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CspReportController 単体テスト")
class CspReportControllerTest {

    @Mock
    private CspReportRepository cspReportRepository;

    /** CspReportService は実装を使う（ロジック検証のため） */
    private CspReportService cspReportService;

    @InjectMocks
    private CspReportController controller;

    private ObjectMapper objectMapper;

    private static final String WRAPPER_BODY = """
            {
              "csp-report": {
                "document-uri": "https://example.com/page",
                "blocked-uri": "https://evil.com/script.js",
                "violated-directive": "script-src",
                "effective-directive": "script-src",
                "original-policy": "default-src 'self'",
                "disposition": "enforce",
                "status-code": 200
              }
            }
            """;

    private static final String NO_WRAPPER_BODY = """
            {
              "document-uri": "https://example.com/page",
              "blocked-uri": "https://evil.com/script.js",
              "violated-directive": "script-src",
              "effective-directive": "script-src",
              "original-policy": "default-src 'self'",
              "disposition": "enforce",
              "status-code": 200
            }
            """;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        cspReportService = new CspReportService(cspReportRepository);
        // Controller を手動で組み立てる（@InjectMocks は ObjectMapper を注入しないため）
        controller = new CspReportController(cspReportService, objectMapper);
    }

    @Test
    @DisplayName("正常系: 標準CSPレポート形式（ラッパーあり）で正常に処理される")
    void receiveCspReport_withWrapper_savesReport() {
        // given
        given(cspReportRepository.findByReportHash(anyString())).willReturn(Optional.empty());
        given(cspReportRepository.save(any(CspReportEntity.class))).willAnswer(inv -> inv.getArgument(0));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.1");
        request.addHeader("User-Agent", "Mozilla/5.0");

        // when / then: 例外が発生しないこと
        assertThatCode(() -> controller.receiveCspReport(WRAPPER_BODY, request))
                .doesNotThrowAnyException();

        // 保存が呼ばれていること
        ArgumentCaptor<CspReportEntity> captor = ArgumentCaptor.forClass(CspReportEntity.class);
        verify(cspReportRepository).save(captor.capture());
        CspReportEntity saved = captor.getValue();
        assertThat(saved.getViolatedDirective()).isEqualTo("script-src");
        assertThat(saved.getDocumentUri()).isEqualTo("https://example.com/page");
        assertThat(saved.getBlockedUri()).isEqualTo("https://evil.com/script.js");
        assertThat(saved.getIpAddress()).isEqualTo("192.168.1.1");
        assertThat(saved.getOccurrenceCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("正常系: ラッパーなし形式でも正常に処理される")
    void receiveCspReport_withoutWrapper_savesReport() {
        // given
        given(cspReportRepository.findByReportHash(anyString())).willReturn(Optional.empty());
        given(cspReportRepository.save(any(CspReportEntity.class))).willAnswer(inv -> inv.getArgument(0));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");

        // when / then: 例外が発生しないこと
        assertThatCode(() -> controller.receiveCspReport(NO_WRAPPER_BODY, request))
                .doesNotThrowAnyException();

        // 保存が呼ばれていること
        verify(cspReportRepository).save(any(CspReportEntity.class));
    }

    @Test
    @DisplayName("正常系: 同じhashのレポートが2回来るとoccurrenceCountが増える")
    void receiveCspReport_duplicateHash_incrementsOccurrenceCount() {
        // given: 初回は空、2回目は既存レコードを返す
        CspReportEntity existing = CspReportEntity.builder()
                .reportHash("some-hash")
                .occurrenceCount(1)
                .violatedDirective("script-src")
                .documentUri("https://example.com/page")
                .blockedUri("https://evil.com/script.js")
                .build();

        given(cspReportRepository.findByReportHash(anyString()))
                .willReturn(Optional.empty())  // 1回目: 新規
                .willReturn(Optional.of(existing));  // 2回目: 既存

        given(cspReportRepository.save(any(CspReportEntity.class))).willAnswer(inv -> inv.getArgument(0));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        // when: 同じボディを2回送信
        assertThatCode(() -> controller.receiveCspReport(WRAPPER_BODY, request))
                .doesNotThrowAnyException();
        assertThatCode(() -> controller.receiveCspReport(WRAPPER_BODY, request))
                .doesNotThrowAnyException();

        // then: 2回 save が呼ばれており、2回目は occurrenceCount が 2 になっていること
        verify(cspReportRepository, times(2)).save(any(CspReportEntity.class));
        assertThat(existing.getOccurrenceCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("異常系: 空のボディでも204が返る（パース失敗は無視）")
    void receiveCspReport_emptyBody_doesNotThrow() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();

        // when / then: 例外が発生しないこと
        assertThatCode(() -> controller.receiveCspReport("", request))
                .doesNotThrowAnyException();
        assertThatCode(() -> controller.receiveCspReport(null, request))
                .doesNotThrowAnyException();

        // Service は呼ばれない（空ボディは早期リターン）
        verify(cspReportRepository, never()).save(any());
    }

    @Test
    @DisplayName("認可: 未認証でも処理される（ServiceのreceiveメソッドがIPアドレスを記録）")
    void receiveCspReport_unauthenticated_processesNormally() {
        // given: SecurityContext には何も設定しない（未認証）
        given(cspReportRepository.findByReportHash(anyString())).willReturn(Optional.empty());
        given(cspReportRepository.save(any(CspReportEntity.class))).willAnswer(inv -> inv.getArgument(0));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.1");  // テスト用 IP アドレス（RFC 5737）

        // when / then: 認証なしでも例外が発生せず正常に処理されること
        assertThatCode(() -> controller.receiveCspReport(WRAPPER_BODY, request))
                .doesNotThrowAnyException();

        ArgumentCaptor<CspReportEntity> captor = ArgumentCaptor.forClass(CspReportEntity.class);
        verify(cspReportRepository).save(captor.capture());
        assertThat(captor.getValue().getIpAddress()).isEqualTo("203.0.113.1");
    }
}
