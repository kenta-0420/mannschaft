package com.mannschaft.app.support.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 統合テスト共通の偽 OAuth プロバイダ（Google / LINE / Apple。JDK 標準の {@link HttpServer}）。
 *
 * <p><b>なぜ共通基底で向けるか</b>（CMP-261121-1217）: 偽サーバのポートを設定へ注入する
 * {@code @DynamicPropertySource} を個別のテストクラスや中間基底（例: {@code MfaItSupport}）に置くと、
 * {@code DynamicPropertiesContextCustomizer} が宣言メソッドそのもの（宣言クラスを含む）で構成を区別するため、
 * 置いたクラスの系統だけ ApplicationContext の構成キーが分かれる（CMP-261111-0508 追撃① の distinct key ラチェット）。
 * そこで {@link AbstractMySqlIntegrationTest#configureProperties} の 1 か所だけから向け、全派生で同じキーを共有する。</p>
 *
 * <p><b>応答の約束</b>: 認可コードに {@code <sub>~<email>} を埋めると、その利用者として token / userinfo / id_token を返す
 * （テストごとに別の OAuth 利用者を作れる）。{@code ~} を含まない認可コード・アクセストークンは実プロバイダと同じく
 * 拒否する（token は 400 {@code invalid_grant}、userinfo は 401）。偽装を意図しない派生（Google カレンダー連携など）が
 * 偽の成功を受け取らないようにするため。</p>
 *
 * <p>サーバは最初に URL が必要になった時（コンテキスト起動時の設定解決）に 1 度だけ起動し、JVM 終了時に止める。</p>
 */
public final class FakeOAuthProviders {

    private static final String SEPARATOR = "~";

    private static HttpServer server;

    private FakeOAuthProviders() {
    }

    /** 偽プロバイダのベース URL（初回呼び出しで起動する）。 */
    public static synchronized String baseUrl() {
        if (server == null) {
            server = start();
            HttpServer started = server;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> started.stop(0)));
        }
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 指定の利用者として振る舞う認可コード（{@code <sub>~<email>}）。 */
    public static String code(String sub, String email) {
        return sub + SEPARATOR + email;
    }

    private static HttpServer start() {
        try {
            HttpServer created = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            created.createContext("/google/token", ex -> {
                String code = codeOf(ex);
                if (isFakeUser(code)) {
                    respond(ex, 200, "{\"access_token\":\"" + code + "\"}");
                } else {
                    rejectGrant(ex);
                }
            });
            created.createContext("/google/userinfo", ex -> {
                String authorization = ex.getRequestHeaders().getFirst("Authorization");
                String token = authorization == null || !authorization.startsWith("Bearer ")
                        ? "" : authorization.substring("Bearer ".length());
                if (!isFakeUser(token)) {
                    respond(ex, 401, "{\"error\":\"invalid_token\"}");
                    return;
                }
                String[] subEmail = splitCode(token);
                respond(ex, 200, "{\"sub\":\"" + subEmail[0] + "\",\"email\":\"" + subEmail[1]
                        + "\",\"given_name\":\"Taro\",\"family_name\":\"Yamada\",\"name\":\"fake-oauth-it\"}");
            });
            created.createContext("/line/token", ex -> {
                String code = codeOf(ex);
                if (isFakeUser(code)) {
                    respond(ex, 200, "{\"access_token\":\"fake-at\",\"id_token\":\"" + idToken(code) + "\"}");
                } else {
                    rejectGrant(ex);
                }
            });
            created.createContext("/apple/token", ex -> {
                String code = codeOf(ex);
                if (isFakeUser(code)) {
                    respond(ex, 200, "{\"id_token\":\"" + idToken(code) + "\"}");
                } else {
                    rejectGrant(ex);
                }
            });
            created.start();
            return created;
        } catch (IOException e) {
            throw new IllegalStateException("偽 OAuth プロバイダの起動に失敗しました", e);
        }
    }

    /** token エンドポイントへ送られた form の code（無ければ空文字）。 */
    private static String codeOf(HttpExchange ex) throws IOException {
        String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        for (String pair : form.split("&")) {
            if (pair.startsWith("code=")) {
                return URLDecoder.decode(pair.substring("code=".length()), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static boolean isFakeUser(String code) {
        String[] subEmail = splitCode(code);
        return subEmail.length == 2 && !subEmail[0].isEmpty() && !subEmail[1].isEmpty();
    }

    private static String[] splitCode(String code) {
        return code.split(SEPARATOR, 2);
    }

    private static String idToken(String code) {
        String[] subEmail = splitCode(code);
        String payload = "{\"sub\":\"" + subEmail[0] + "\",\"email\":\"" + subEmail[1] + "\",\"name\":\"fake-oauth-it\"}";
        return Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
                + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + ".sig";
    }

    private static void rejectGrant(HttpExchange ex) throws IOException {
        respond(ex, 400, "{\"error\":\"invalid_grant\"}");
    }

    private static void respond(HttpExchange ex, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
