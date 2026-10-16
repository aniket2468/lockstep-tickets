package com.locksteptickets.api;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class JsonRequestSizeFilter extends OncePerRequestFilter {

    private final int maximumBytes;
    private final ObjectMapper objectMapper;

    JsonRequestSizeFilter(
        @Value("${lockstep.http.max-json-request-bytes:65536}") int maximumBytes,
        ObjectMapper objectMapper
    ) {
        if (maximumBytes < 1 || maximumBytes > 1_048_576) {
            throw new IllegalArgumentException(
                "lockstep.http.max-json-request-bytes must be between 1 and 1048576");
        }
        this.maximumBytes = maximumBytes;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null) {
            return true;
        }
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        boolean json = MediaType.APPLICATION_JSON_VALUE.equals(mediaType)
            || mediaType.endsWith("+json");
        return !json || !("POST".equals(request.getMethod())
            || "PUT".equals(request.getMethod())
            || "PATCH".equals(request.getMethod()));
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maximumBytes) {
            reject(response, request);
            return;
        }

        byte[] body = request.getInputStream().readNBytes(maximumBytes + 1);
        if (body.length > maximumBytes) {
            reject(response, request);
            return;
        }
        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private void reject(HttpServletResponse response, HttpServletRequest request) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        objectMapper.writeValue(response.getOutputStream(), ApiError.of(
            "PAYLOAD_TOO_LARGE",
            "The JSON request body exceeds the allowed size.",
            RequestIdFilter.from(request)));
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("Asynchronous reads are not supported");
                }

                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public int read(byte[] bytes, int offset, int length) {
                    return input.read(bytes, offset, length);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(),
                getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8
                    : java.nio.charset.Charset.forName(getCharacterEncoding())));
        }
    }
}
