package com.mannschaft.app.support.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/** 統合試験の実 HTTP 呼び出しを受ける、JVM 内で共有するループバック OAuth プロバイダ。 */
final class FakeOAuthProviders {

    private FakeOAuthProviders() {
    }

    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("mannschaft.google.token-uri", () -> endpoint("/google/token"));
        registry.add("mannschaft.google.userinfo-uri", () -> endpoint("/google/userinfo"));
        registry.add("mannschaft.line.token-uri", () -> endpoint("/line/token"));
        registry.add("mannschaft.apple.token-uri", () -> endpoint("/apple/token"));
    }

    private static String endpoint(String path) {
        return "http://127.0.0.1:" + SharedServer.INSTANCE.getAddress().getPort() + path;
    }

    private static class SharedServer {
        private static final HttpServer INSTANCE = startFakeProviders();
    }

    private static HttpServer startFakeProviders() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            try {
                server.createContext("/google/token", ex -> respond(ex,
                        "{\"access_token\":\"" + codeOf(ex) + "\"}"));
                server.createContext("/google/userinfo", ex -> {
                    String[] subEmail = splitCode(ex.getRequestHeaders().getFirst("Authorization")
                            .substring("Bearer ".length()));
                    respond(ex, "{\"sub\":\"" + subEmail[0] + "\",\"email\":\"" + subEmail[1]
                            + "\",\"given_name\":\"Taro\",\"family_name\":\"Yamada\",\"name\":\"oauth-mfa-it\"}");
                });
                server.createContext("/line/token", ex -> respond(ex,
                        "{\"access_token\":\"fake-at\",\"id_token\":\"" + idToken(codeOf(ex)) + "\"}"));
                server.createContext("/apple/token", ex -> respond(ex,
                        "{\"id_token\":\"" + idToken(codeOf(ex)) + "\"}"));
                startOnDaemon(server);
                Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0), "test-oauth-provider-stop"));
                return server;
            } catch (RuntimeException failure) {
                server.stop(0);
                throw failure;
            }
        } catch (IOException failure) {
            throw new IllegalStateException("偽 OAuth プロバイダを確保できません", failure);
        }
    }

    private static void startOnDaemon(HttpServer server) {
        // HttpServer の dispatcher は起動元の daemon 属性を継承する。
        FutureTask<Void> start = new FutureTask<>(server::start, null);
        Thread.ofPlatform().daemon().name("test-oauth-provider-start").start(start);
        try {
            start.get();
        } catch (InterruptedException failure) {
            start.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("偽 OAuth プロバイダの起動が中断されました", failure);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("偽 OAuth プロバイダを起動できません", failure.getCause());
        }
    }

    /** 各試験が渡した {@code <sub>~<email>} を token リクエストの form から取得する。 */
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
        String payload = "{\"sub\":\"" + subEmail[0] + "\",\"email\":\"" + subEmail[1]
                + "\",\"name\":\"oauth-mfa-it\"}";
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
