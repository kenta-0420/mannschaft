package com.mannschaft.app.loyalty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** チームポイントJSON本文の4KB境界。認証フィルターの後で検査する。 */
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 1)
public class LoyaltyBodyLimitFilter extends OncePerRequestFilter {
    private static final int MAX_BODY_BYTES = 4096;
    private final ObjectMapper objectMapper;

    public LoyaltyBodyLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.matches("/api/v1/loyalty(?:/.*)?")
                && !path.matches("/api/v1/teams/[^/]+/loyalty(?:/.*)?");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            reject(response);
            return;
        }
        // Content-Lengthなし、または小さく偽装された本文も同じ有限バイト数で判定する。
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            reject(response);
            return;
        }
        chain.doFilter(new BoundedBodyRequest(request, body), response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(CommonErrorCode.COMMON_001));
    }

    /** 検査済み本文のみをMVCへ再生し、機密値はログや例外文言へコピーしない。 */
    private static final class BoundedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private BoundedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public String getCharacterEncoding() { return StandardCharsets.UTF_8.name(); }
        @Override public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
        @Override public ServletInputStream getInputStream() {
            return new ServletInputStream() {
                private final ByteArrayInputStream input = new ByteArrayInputStream(body);
                private boolean listenerRegistered;
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] target, int offset, int length) {
                    return input.read(target, offset, length);
                }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    if (listener == null || listenerRegistered) {
                        throw new IllegalStateException("ReadListenerは一度だけ指定できます");
                    }
                    listenerRegistered = true;
                    try {
                        if (!isFinished()) listener.onDataAvailable();
                        if (isFinished()) listener.onAllDataRead();
                    } catch (IOException failure) {
                        listener.onError(failure);
                    }
                }
            };
        }
    }
}
