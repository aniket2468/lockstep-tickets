package com.locksteptickets.auth;

import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

@Component
public final class ClientIpResolver {

    private static final Pattern IP_LITERAL = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
    private final List<IpAddressMatcher> trustedProxies;

    ClientIpResolver(@Value("${lockstep.http.trusted-proxy-cidrs:}") String configuredCidrs) {
        trustedProxies = configuredCidrs.lines()
            .flatMap(line -> List.of(line.split(",")).stream())
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(IpAddressMatcher::new)
            .toList();
    }

    String resolve(HttpServletRequest request) {
        String immediatePeer = canonicalIp(request.getRemoteAddr());
        if (immediatePeer == null || !isTrusted(immediatePeer)) {
            return immediatePeer == null ? "invalid" : immediatePeer;
        }

        List<String> forwarded = forwardedAddresses(request);
        for (int index = forwarded.size() - 1; index >= 0; index--) {
            String candidate = canonicalIp(forwarded.get(index));
            if (candidate == null) {
                return immediatePeer;
            }
            if (!isTrusted(candidate)) {
                return candidate;
            }
        }
        return immediatePeer;
    }

    private List<String> forwardedAddresses(HttpServletRequest request) {
        var values = new ArrayList<String>();
        var headers = request.getHeaders("X-Forwarded-For");
        while (headers.hasMoreElements()) {
            for (String value : headers.nextElement().split(",")) {
                values.add(value.trim());
            }
        }
        return values;
    }

    private boolean isTrusted(String address) {
        return trustedProxies.stream().anyMatch(matcher -> matcher.matches(address));
    }

    private String canonicalIp(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        if (!IP_LITERAL.matcher(candidate).matches()) {
            return null;
        }
        if (candidate.contains(".")) {
            String[] octets = candidate.split("\\.", -1);
            if (octets.length != 4) {
                return null;
            }
            var canonical = new StringBuilder();
            for (String octet : octets) {
                if (octet.isEmpty() || octet.length() > 3
                    || !octet.chars().allMatch(Character::isDigit)) {
                    return null;
                }
                int valueAsNumber = Integer.parseInt(octet);
                if (valueAsNumber > 255) {
                    return null;
                }
                if (!canonical.isEmpty()) {
                    canonical.append('.');
                }
                canonical.append(valueAsNumber);
            }
            return canonical.toString();
        }
        if (!candidate.contains(":")) {
            return null;
        }
        try {
            InetAddress parsed = InetAddress.getByName(candidate);
            return parsed instanceof Inet6Address ? parsed.getHostAddress() : null;
        } catch (UnknownHostException exception) {
            return null;
        }
    }
}
