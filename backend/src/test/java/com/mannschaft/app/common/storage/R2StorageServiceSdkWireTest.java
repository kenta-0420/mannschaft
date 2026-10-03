package com.mannschaft.app.common.storage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP1041/#1489: 実SDKの要求をloopbackだけで捕捉する現契約の固定試練。
 * 旧2.29.45と新2.46.18を別classpathで測定する。版をこの試験内で切り替えない。
 * serverは署名/checksumの受理を模倣しないため、成功を実R2互換・実ブラウザ成功とは扱わない。
 * HttpServerのHTTP transfer chunk/trailerは未観測で、aws-chunked本文とは区別する。
 */
@DisplayName("R2 SDK wire契約（dummy/loopback限定、provider受理は対象外）")
@Timeout(120)
class R2StorageServiceSdkWireTest {
    private static final String BUCKET = "cmp1041-dummy-bucket";
    private static final String KEY = "dummy/test.bin";
    private static final String CONTENT_TYPE = "application/octet-stream";
    private static final String UPLOAD_ID = "cmp1041-dummy-upload";
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int BODY_LIMIT = 1024 * 1024;
    private static final int REQUEST_LIMIT = 4;
    private static final byte[] PAYLOAD = "cmp1041-dummy-payload\r\n".repeat(256).getBytes(StandardCharsets.UTF_8);

    private final ArrayBlockingQueue<Capture> captures = new ArrayBlockingQueue<>(REQUEST_LIMIT);
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private HttpServer server;
    private ExecutorService serverExecutor;
    private ScheduledExecutorService watchdog;
    private SdkHttpClient transport;
    private S3Client client;
    private S3Presigner presigner;
    private R2StorageService storage;
    private URI endpoint;

    @BeforeEach
    void 準備_既存checksum設定のままloopbackへ限定する(TestReporter reporter) throws Exception {
        // BOM宣言やcacheの版を推測せず、実classpathのjar名と内容hashを各caseへ残す。
        publish(reporter, "cmp1041.runtime.s3", runtimeJar(S3Client.class));
        publish(reporter, "cmp1041.runtime.sdk-core", runtimeJar(ClientOverrideConfiguration.class));
        publish(reporter, "cmp1041.runtime.apache-client", runtimeJar(ApacheHttpClient.class));
        // 共有環境のchecksum上書きを消さず、baselineを変える条件なら明示失敗にする。
        for (String name : List.of("AWS_REQUEST_CHECKSUM_CALCULATION", "AWS_RESPONSE_CHECKSUM_VALIDATION")) {
            assertThat(System.getenv(name)).as("baselineを変える環境変数: %s", name).isNull();
        }
        for (String name : List.of("aws.requestChecksumCalculation", "aws.responseChecksumValidation")) {
            assertThat(System.getProperty(name)).as("baselineを変えるJVM設定: %s", name).isNull();
        }
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        serverExecutor = Executors.newFixedThreadPool(2);
        watchdog = Executors.newSingleThreadScheduledExecutor();
        server.setExecutor(serverExecutor);
        server.createContext("/", this::captureRequest);
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());

        transport = ApacheHttpClient.builder()
                .proxyConfiguration(ProxyConfiguration.builder()
                        .useSystemPropertyValues(false).useEnvironmentVariableValues(false).build())
                .connectionTimeout(Duration.ofSeconds(5))
                .connectionAcquisitionTimeout(Duration.ofSeconds(5))
                .socketTimeout(Duration.ofSeconds(5))
                .maxConnections(2)
                .build();
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("dummy-access", "dummy-secret"));
        client = S3Client.builder().region(Region.of("auto"))
                .credentialsProvider(credentials).endpointOverride(endpoint).forcePathStyle(true)
                .httpClient(transport)
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .retryPolicy(RetryPolicy.forRetryMode(RetryMode.STANDARD))
                        .apiCallAttemptTimeout(Duration.ofSeconds(30)).apiCallTimeout(Duration.ofSeconds(90))
                        .addExecutionInterceptor(new ExecutionInterceptor() {
                            @Override
                            public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
                                requireLoopback(context.httpRequest().getUri());
                            }
                        }).build())
                .build();
        presigner = S3Presigner.builder().region(Region.of("auto"))
                .credentialsProvider(credentials).endpointOverride(endpoint)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
        storage = new R2StorageService(client, presigner, new StorageProperties(BUCKET, 900, 3600));
    }

    @AfterEach
    void 終了_自所有の接続とserverだけを閉じる() throws Exception {
        try {
            if (client != null) client.close();
        } finally {
            try {
                if (presigner != null) presigner.close();
            } finally {
                try {
                    if (transport != null) transport.close();
                } finally {
                    if (server != null) server.stop(0);
                    if (watchdog != null) watchdog.shutdownNow();
                    if (serverExecutor != null) serverExecutor.shutdownNow();
                }
            }
        }
        if (watchdog != null) assertThat(watchdog.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        if (serverExecutor != null) assertThat(serverExecutor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(serverFailure.get()).as("loopback捕捉の途中失敗").isNull();
    }

    @Test
    void bytesPUT_実SDK送信_元payloadが保持される(TestReporter reporter) throws Exception {
        storage.upload(KEY, PAYLOAD, CONTENT_TYPE);

        Capture capture = take(reporter, "bytes-put");
        assertPutPayload(capture);
    }

    @Test
    void streamPUT_実SDK送信_元payloadが保持される(TestReporter reporter) throws Exception {
        try (var stream = new ByteArrayInputStream(PAYLOAD)) {
            storage.upload(KEY, stream, PAYLOAD.length, CONTENT_TYPE);
        }

        Capture capture = take(reporter, "stream-put");
        assertPutPayload(capture);
    }

    @Test
    void 単PUT署名_現ブラウザのContentTypeだけで必要headerを満たす(TestReporter reporter) throws Exception {
        var result = storage.generateUploadUrl(KEY, CONTENT_TYPE, TTL);
        var signed = presigner.presignPutObject(PutObjectPresignRequest.builder().signatureDuration(TTL)
                .putObjectRequest(PutObjectRequest.builder().bucket(BUCKET).key(KEY).contentType(CONTENT_TYPE).build())
                .build());

        assertPresign(reporter, "single-put-presign", URI.create(result.uploadUrl()), signed.signedHeaders(),
                signed.isBrowserExecutable(), Set.of("host", "content-type"));
        assertThat(URI.create(result.uploadUrl()).getPath()).isEqualTo("/" + BUCKET + "/" + KEY);
        assertThat(query(URI.create(result.uploadUrl()))).containsEntry("X-Amz-Expires", "300");
        assertThat(requestCount.get()).as("presignは外部にもloopbackにも送信しない").isZero();
    }

    @Test
    void multipartCreate_algorithm未指定_UploadIdと要求を捕捉する(TestReporter reporter) throws Exception {
        String result = storage.createMultipartUpload(KEY, CONTENT_TYPE);

        Capture capture = take(reporter, "multipart-create");
        assertThat(result).isEqualTo(UPLOAD_ID);
        assertThat(capture.method()).isEqualTo("POST");
        assertThat(capture.query()).containsKey("uploads");
        assertThat(capture.headers()).containsEntry("content-type", List.of(CONTENT_TYPE));
    }

    @Test
    void multipartPart署名_現ブラウザの明示header無し契約を満たす(TestReporter reporter) throws Exception {
        var urls = storage.createPresignedPartUrls(KEY, UPLOAD_ID, List.of(1, 2), TTL);

        assertThat(urls).hasSize(2);
        for (int index = 0; index < urls.size(); index++) {
            var signed = presigner.presignUploadPart(UploadPartPresignRequest.builder().signatureDuration(TTL)
                    .uploadPartRequest(UploadPartRequest.builder().bucket(BUCKET).key(KEY).uploadId(UPLOAD_ID)
                            .partNumber(index + 1).build()).build());
            URI actualUrl = URI.create(urls.get(index).uploadUrl());
            assertPresign(reporter, "multipart-part-" + (index + 1), actualUrl, signed.signedHeaders(),
                    signed.isBrowserExecutable(), Set.of("host"));
            assertThat(urls.get(index).partNumber()).isEqualTo(index + 1);
            assertThat(query(actualUrl)).containsEntry("uploadId", UPLOAD_ID)
                    .containsEntry("partNumber", Integer.toString(index + 1));
        }
        assertThat(requestCount.get()).isZero();
    }

    @Test
    void multipartComplete_ETagだけの既存契約_XML対応を保持する(TestReporter reporter) throws Exception {
        storage.completeMultipartUpload(KEY, UPLOAD_ID, List.of(
                CompletedPart.builder().partNumber(1).eTag("dummy-etag-1").build(),
                CompletedPart.builder().partNumber(2).eTag("dummy-etag-2").build()));

        Capture capture = take(reporter, "multipart-complete");
        assertThat(capture.method()).isEqualTo("POST");
        assertThat(capture.query()).containsEntry("uploadId", UPLOAD_ID);
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(decode(capture).payload()));
        assertThat(document.getElementsByTagNameNS("*", "Part").getLength()).isEqualTo(2);
        for (int index = 0; index < 2; index++) {
            assertThat(document.getElementsByTagNameNS("*", "PartNumber").item(index).getTextContent())
                    .isEqualTo(Integer.toString(index + 1));
            assertThat(document.getElementsByTagNameNS("*", "ETag").item(index).getTextContent())
                    .isEqualTo("dummy-etag-" + (index + 1));
        }
    }

    private void requireLoopback(URI uri) {
        if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                || uri.getPort() != endpoint.getPort() || !uri.getPath().equals("/" + BUCKET + "/" + KEY)) {
            throw new IllegalStateException("固定loopback以外へのSDK送信を拒否");
        }
    }

    private void captureRequest(HttpExchange exchange) throws IOException {
        var deadline = watchdog.schedule(exchange::close, 8, TimeUnit.SECONDS);
        try {
            if (requestCount.incrementAndGet() > REQUEST_LIMIT) throw new IOException("request捕捉上限超過");
            byte[] body = exchange.getRequestBody().readNBytes(BODY_LIMIT + 1);
            if (body.length > BODY_LIMIT) throw new IOException("body捕捉上限超過");
            Map<String, List<String>> headers = new TreeMap<>();
            exchange.getRequestHeaders().forEach((key, value) -> {
                if (!key.equalsIgnoreCase("authorization")) headers.put(key.toLowerCase(java.util.Locale.ROOT), List.copyOf(value));
            });
            var capture = new Capture(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    query(exchange.getRequestURI()), headers, body);
            if (!captures.offer(capture)) throw new IOException("捕捉queue上限超過");
            byte[] response = responseFor(capture).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            if ("PUT".equals(capture.method()) && !capture.query().containsKey("uploadId")) {
                // 単一PUTのETagはSDKのMD5応答検証対象。aws-chunkedを除いた実payloadで応答する。
                String etag = HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                        .digest(decode(capture).payload()));
                exchange.getResponseHeaders().set("ETag", "\"" + etag + "\"");
            } else {
                exchange.getResponseHeaders().set("ETag", "dummy-response-etag");
            }
            exchange.sendResponseHeaders(200, response.length == 0 ? -1 : response.length);
            if (response.length > 0) exchange.getResponseBody().write(response);
        } catch (Throwable failure) {
            serverFailure.compareAndSet(null, failure);
        } finally {
            deadline.cancel(false);
            exchange.close();
        }
    }

    private String responseFor(Capture capture) {
        if (capture.query().containsKey("uploads")) {
            return "<InitiateMultipartUploadResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                    + "<Bucket>" + BUCKET + "</Bucket><Key>" + KEY + "</Key><UploadId>" + UPLOAD_ID
                    + "</UploadId></InitiateMultipartUploadResult>";
        }
        if (capture.query().containsKey("uploadId")) {
            return "<CompleteMultipartUploadResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                    + "<Bucket>" + BUCKET + "</Bucket><Key>" + KEY
                    + "</Key><ETag>dummy-response-etag</ETag></CompleteMultipartUploadResult>";
        }
        return "";
    }

    private Capture take(TestReporter reporter, String name) throws Exception {
        Capture capture = captures.poll(5, TimeUnit.SECONDS);
        assertThat(capture).as("SDK request捕捉: %s", name).isNotNull();
        publish(reporter, "cmp1041.wire." + name, "method=" + capture.method() + "; path=" + capture.path()
                + "; query=" + capture.query() + "; headers=" + capture.headers()
                + "; bodyBytes=" + capture.body().length + "; rawSHA256=" + sha256(capture.body())
                + "; HTTP-transfer-trailer=未観測; provider-validation=未実施");
        Decoded decoded = decode(capture);
        publish(reporter, "cmp1041.wire." + name + ".decoded", "payloadBytes=" + decoded.payload().length
                + "; decodedSHA256=" + sha256(decoded.payload()) + "; aws-body-trailers=" + decoded.trailers());
        assertThat(requestCount.get()).as("正常応答を再試行で隠さない").isEqualTo(1);
        assertThat(capture.path()).isEqualTo("/" + BUCKET + "/" + KEY);
        return capture;
    }

    private void assertPutPayload(Capture capture) throws Exception {
        assertThat(capture.method()).isEqualTo("PUT");
        assertThat(capture.headers()).containsEntry("content-type", List.of(CONTENT_TYPE));
        Decoded decoded = decode(capture);
        assertThat(decoded.payload()).isEqualTo(PAYLOAD);
        String checksum = capture.headers().getOrDefault("x-amz-checksum-crc32", List.of())
                .stream().findFirst().orElse(decoded.trailers().get("x-amz-checksum-crc32"));
        if (checksum != null) {
            assertThat(checksum).isEqualTo(payloadCrc32());
        }
    }

    private void assertPresign(TestReporter reporter, String name, URI uri, Map<String, List<String>> signedHeaders,
                               boolean browserExecutable, Set<String> browserHeaders)
            throws Exception {
        requireLoopback(uri);
        Map<String, String> parameters = query(uri);
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(signedHeaders);
        publish(reporter, "cmp1041.wire." + name, "query=" + parameters + "; signedHeaders=" + headers
                + "; isBrowserExecutable=" + browserExecutable + "; actual-browser=未実施");
        assertThat(parameters).containsEntry("X-Amz-Expires", "300");
        assertThat(parameters).containsKey("X-Amz-SignedHeaders");
        assertThat(List.of(parameters.get("X-Amz-SignedHeaders").split(";")))
                .allSatisfy(key -> assertThat(browserHeaders).contains(key.toLowerCase(java.util.Locale.ROOT)));
        assertThat(headers.keySet()).allSatisfy(key -> assertThat(browserHeaders).contains(key.toLowerCase(java.util.Locale.ROOT)));
        assertThat(headers).doesNotContainKey("cache-control");
        if (headers.containsKey("content-type")) assertThat(headers.get("content-type")).containsExactly(CONTENT_TYPE);
        // URLに固定checksumが入る場合も、bodyだけを送る現FEのdummy payloadと一致する必要がある。
        parameters.forEach((key, value) -> {
            if (key.equalsIgnoreCase("x-amz-checksum-crc32")) assertThat(value).isEqualTo(payloadCrc32());
        });
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> result = new TreeMap<>();
        if (uri.getRawQuery() == null) return result;
        for (String entry : uri.getRawQuery().split("&")) {
            String[] pair = entry.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            result.put(key, key.equalsIgnoreCase("X-Amz-Signature") || key.equalsIgnoreCase("X-Amz-Credential")
                    ? "dummy-redacted" : value);
        }
        return result;
    }

    /** HTTP transfer chunkはHttpServerが解いた後。残るaws-chunked本文だけを有限範囲で読む。 */
    private static Decoded decode(Capture capture) throws IOException {
        boolean awsChunked = capture.headers().getOrDefault("content-encoding", List.of())
                .stream().anyMatch(value -> value.contains("aws-chunked"));
        if (!awsChunked) return new Decoded(capture.body(), Map.of());
        var input = new ByteArrayInputStream(capture.body());
        var output = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(input);
            int separator = line.indexOf(';');
            int length = Integer.parseInt(separator < 0 ? line : line.substring(0, separator), 16);
            if (length < 0 || length > BODY_LIMIT - output.size()) throw new IOException("aws chunk長の範囲外");
            if (length == 0) break;
            byte[] chunk = input.readNBytes(length);
            if (chunk.length != length || input.read() != '\r' || input.read() != '\n') throw new IOException("aws chunk欠損");
            output.write(chunk);
        }
        Map<String, String> trailers = new TreeMap<>();
        while (input.available() > 0) {
            String line = readLine(input);
            if (line.isEmpty()) continue;
            int separator = line.indexOf(':');
            if (separator < 0) throw new IOException("aws trailer形式不正");
            trailers.put(line.substring(0, separator).toLowerCase(java.util.Locale.ROOT), line.substring(separator + 1).trim());
        }
        return new Decoded(output.toByteArray(), trailers);
    }

    private static String readLine(ByteArrayInputStream input) throws IOException {
        var line = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\r') {
                if (input.read() != '\n') throw new IOException("CRLF欠損");
                return line.toString(StandardCharsets.US_ASCII);
            }
            if (line.size() >= 8192) throw new IOException("aws chunk行の範囲外");
            line.write(value);
        }
        throw new IOException("aws chunk行の途中終了");
    }

    private static String sha256(byte[] body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }

    private static String payloadCrc32() {
        CRC32 crc = new CRC32();
        crc.update(PAYLOAD);
        byte[] value = java.nio.ByteBuffer.allocate(4).putInt((int) crc.getValue()).array();
        return Base64.getEncoder().encodeToString(value);
    }

    /** 既存の秘匿済み観測だけを、TestReporterとGradle XMLのsystem-outへ同じ値で残す。 */
    private static void publish(TestReporter reporter, String key, String value) {
        reporter.publishEntry(key, value);
        System.out.println(key + "=" + value);
    }

    /** 実jarのbasenameだけを記録し、例外にも絶対userpathや元URLを載せない。 */
    private static String runtimeJar(Class<?> type) {
        try {
            var source = type.getProtectionDomain().getCodeSource();
            if (source == null) throw new IllegalStateException("CodeSource不在");
            URI location = source.getLocation().toURI();
            if (!"file".equals(location.getScheme())) throw new IllegalStateException("file以外のartifact");
            Path jar = Path.of(location);
            if (!Files.isRegularFile(jar) || !jar.getFileName().toString().endsWith(".jar")) {
                throw new IllegalStateException("実jarを同定できない");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(jar)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return "jar=" + jar.getFileName() + "; sha256=" + HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            // causeにもcache/userpathが含まれ得るため、失敗種別だけで未同定を明示する。
            throw new IllegalStateException("実SDK artifact同定失敗: " + type.getName()
                    + " / " + failure.getClass().getSimpleName());
        }
    }

    private record Capture(String method, String path, Map<String, String> query,
                           Map<String, List<String>> headers, byte[] body) {}
    private record Decoded(byte[] payload, Map<String, String> trailers) {}
}
