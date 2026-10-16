package com.locksteptickets.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class JsonRequestSizeFilterTest {

    private final JsonRequestSizeFilter filter = new JsonRequestSizeFilter(16,
        new ObjectMapper());

    @Test
    void rejectsOversizedDeclaredBodyWithoutInvokingChain() throws Exception {
        var request = jsonRequest("0123456789abcdefg");
        var response = new MockHttpServletResponse();
        var invoked = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> invoked.set(true));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
        assertThat(invoked).isFalse();
    }

    @Test
    void rejectsOversizedBodyWithoutContentLength() throws Exception {
        var original = jsonRequest("0123456789abcdefg");
        var request = new MockHttpServletRequest() {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setMethod(original.getMethod());
        request.setContentType(original.getContentType());
        request.setContent(original.getContentAsByteArray());
        var response = new MockHttpServletResponse();
        var invoked = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> invoked.set(true));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(invoked).isFalse();
    }

    @Test
    void passesBodyAtExactLimitThroughReplayableRequest() throws Exception {
        var request = jsonRequest("0123456789abcdef");
        var response = new MockHttpServletResponse();
        var observed = new StringBuilder();

        filter.doFilter(request, response, (wrapped, ignoredResponse) -> observed.append(
            new String(wrapped.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(observed).hasToString("0123456789abcdef");
    }

    private MockHttpServletRequest jsonRequest(String body) {
        var request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
