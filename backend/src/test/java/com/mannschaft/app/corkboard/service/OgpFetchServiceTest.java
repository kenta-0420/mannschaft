package com.mannschaft.app.corkboard.service;

import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
import com.mannschaft.app.corkboard.repository.CorkboardCardRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OgpFetchService 単体テスト")
class OgpFetchServiceTest {

    private HttpServer server;
    private int port;
    private CorkboardCardRepository cardRepository;
    private OgpFetchService service;

    @BeforeEach
    void setUp() throws IOException {
        cardRepository = mock(CorkboardCardRepository.class);
        service = new OgpFetchService(cardRepository);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void route(String path, String html) {
        server.createContext(path, exchange -> {
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    @Test
    @DisplayName("og:title / og:image / og:description を抽出する")
    void og属性抽出() {
        String html = """
                <html><head>
                <meta property="og:title" content="サンプルタイトル">
                <meta property="og:image" content="https://example.com/img.png">
                <meta property="og:description" content="サンプル説明">
                </head><body>本文</body></html>
                """;
        route("/ok", html);

        OgpFetchService.OgpMeta meta = service.fetch(url("/ok"));

        assertThat(meta).isNotNull();
        assertThat(meta.title()).isEqualTo("サンプルタイトル");
        assertThat(meta.imageUrl()).isEqualTo("https://example.com/img.png");
        assertThat(meta.description()).isEqualTo("サンプル説明");
    }

    @Test
    @DisplayName("og:title 不在時は <title> をフォールバック採用")
    void titleフォールバック() {
        String html = "<html><head><title>HTMLタイトル</title></head><body>本文</body></html>";
        route("/notitle", html);

        OgpFetchService.OgpMeta meta = service.fetch(url("/notitle"));

        assertThat(meta).isNotNull();
        assertThat(meta.title()).isEqualTo("HTMLタイトル");
        assertThat(meta.imageUrl()).isNull();
        assertThat(meta.description()).isNull();
    }

    @Test
    @DisplayName("接続失敗時は null（取得失敗を握りつぶさず null として呼び元に通知）")
    void 接続失敗() {
        // 適当な閉じているポートへ
        OgpFetchService.OgpMeta meta = service.fetch("http://127.0.0.1:1/missing");
        assertThat(meta).isNull();
    }

    @Test
    @DisplayName("fetchAndUpdate: 成功時はカードに OGP メタが反映される")
    void 成功時カード更新() {
        String html = """
                <html><head>
                <meta property="og:title" content="ページA">
                <meta property="og:image" content="https://example.com/a.png">
                </head></html>
                """;
        route("/page-a", html);

        CorkboardCardEntity card = CorkboardCardEntity.builder()
                .corkboardId(1L).cardType("URL").url(url("/page-a")).createdBy(1L).build();
        when(cardRepository.findById(100L)).thenReturn(Optional.of(card));

        service.fetchAndUpdate(100L, url("/page-a"));

        assertThat(card.getOgTitle()).isEqualTo("ページA");
        assertThat(card.getOgImageUrl()).isEqualTo("https://example.com/a.png");
        verify(cardRepository).save(card);
    }

    @Test
    @DisplayName("fetchAndUpdate: 取得失敗時はカードを更新しない")
    void 失敗時カード未更新() {
        // 取得失敗 URL を渡す
        service.fetchAndUpdate(100L, "http://127.0.0.1:1/missing");
        verify(cardRepository, org.mockito.Mockito.never()).save(any(CorkboardCardEntity.class));
    }

    @Test
    @DisplayName("カード ID / URL が null/空ならノーオペ")
    void null入力ノーオペ() {
        service.fetchAndUpdate(null, "http://example.com");
        service.fetchAndUpdate(1L, null);
        service.fetchAndUpdate(1L, "");
        verify(cardRepository, org.mockito.Mockito.never()).findById(any());
    }

    @Test
    @DisplayName("DB 列長を超える OGP 値は切り詰められる")
    void 列長切り詰め() {
        String longTitle = "あ".repeat(500); // 200 で切り詰めされる想定
        String html = "<html><head><meta property=\"og:title\" content=\"" + longTitle + "\"></head></html>";
        route("/long", html);

        OgpFetchService.OgpMeta meta = service.fetch(url("/long"));
        assertThat(meta).isNotNull();
        assertThat(meta.title().length()).isEqualTo(200);
    }
}
