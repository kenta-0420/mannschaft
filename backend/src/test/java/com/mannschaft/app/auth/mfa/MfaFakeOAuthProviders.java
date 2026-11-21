package com.mannschaft.app.auth.mfa;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * MFA 系 IT 共通の偽 OAuth プロバイダ（Google / LINE / Apple。JDK 標準の {@link HttpServer}）。
 *
 * <p>偽サーバのポートを {@code @DynamicPropertySource} で設定へ注入するため、これを個別のテストクラスに置くと
 * ApplicationContext の構成キーが 1 つ増える（CMP-261111-0508 追撃① の distinct key ラチェット）。
 * そこで {@link MfaItSupport} に 1 度だけ宣言し、MFA 系 IT 全体で同じキーを共有する。
 * サーバは最初に必要になった時に起動し、JVM 終了時に止める（認可コードに {@code <sub>~<email>} を埋めて返す方式）。</p>
 */
final class MfaFakeOAuthProviders {

    private static HttpServer server;

    private MfaFakeOAuthProviders() {
    }

    /** 偽プロバイダのベース URL（初回呼び出しで起動する）。 */
    static synchronized String baseUrl() {
        if (server == null) {
            server = startFakeProviders();
            HttpServer started = server;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> started.stop(0)));
        }
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static HttpServer startFakeProviders() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/google/token", ex -> respond(ex,
                    "{\"access_token\":\"" + codeOf(ex) + "\"}"));
            server.createContext("/google/userinfo", ex -> {
                String[] subEmail = splitCode(ex.getRequestHeaders().getFirst("Authorization").substring("Bearer ".length()));
                respond(ex, "{\"sub\":\"" + subEmail[0] + "\",\"email\":\"" + subEmail[1]
                        + "\",\"given_name\":\"Taro\",\"family_name\":\"Yamada\",\"name\":\"oauth-mfa-it\"}");
            });
            server.createContext("/line/token", ex -> respond(ex,
                    "{\"access_token\":\"fake-at\",\"id_token\":\"" + idToken(codeOf(ex)) + "\"}"));
            server.createContext("/apple/token", ex -> respond(ex,
                    "{\"id_token\":\"" + idToken(codeOf(ex)) + "\"}"));
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("偽 OAuth プロバイダの起動に失敗しました", e);
        }
    }

    /** token エンドポイントへ送られた form の code（= {@code <sub>~<email>}）。 */
    private static String codeOf(HttpExchange ex) throws IOException {
        String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        for (String pair : form.split("&")) {
            if (pair.startsWith("code=")) {
                return URLDecoder.decode(pair.substring("code=".length()), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("code が送られていない: " + form);
    }

    private static String[] splitCode(String code) {
        return code.split("~", 2);
    }

    private static String idToken(String code) {
        String[] subEmail = splitCode(code);
        String payload = "{\"sub\":\"" + subEmail[0] + "\",\"email\":\"" + subEmail[1] + "\",\"name\":\"oauth-mfa-it\"}";
        return Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
                + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + ".sig";
    }

    private static void respond(HttpExchange ex, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
