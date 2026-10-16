package com.locksteptickets.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver("172.16.0.0/12");

    @Test
    void takesRightmostUntrustedAddressBehindTrustedProxy() {
        var request = request("172.20.0.5", "198.51.100.200, 203.0.113.10");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.10");
    }

    @Test
    void ignoresForwardingHeaderFromUntrustedPeer() {
        var request = request("203.0.113.10", "198.51.100.200");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.10");
    }

    @Test
    void failsClosedToImmediateProxyForMalformedChain() {
        var request = request("172.20.0.5", "not-an-address");

        assertThat(resolver.resolve(request)).isEqualTo("172.20.0.5");
    }

    @Test
    void rejectsAbbreviatedIpv4InForwardingChain() {
        var request = request("172.20.0.5", "203.0.113");

        assertThat(resolver.resolve(request)).isEqualTo("172.20.0.5");
    }

    @Test
    void ignoresForwardingHeadersWhenNoProxyIsConfigured() {
        var request = request("203.0.113.10", "198.51.100.200");

        assertThat(new ClientIpResolver("").resolve(request)).isEqualTo("203.0.113.10");
    }

    private MockHttpServletRequest request(String peer, String forwardedFor) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
    }
}
