package com.krister.avatar.api;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;

// Validates submitted URLs at the API boundary before any job is queued.
//
// Two security concerns are addressed here:
// 1. Format correctness — reject garbage before it reaches the worker.
// 2. SSRF (Server-Side Request Forgery) prevention — an attacker could submit a URL
//    pointing to an internal service (e.g. http://169.254.169.254 is the AWS instance
//    metadata endpoint) and trick the worker into fetching secrets on their behalf.
//    We prevent this by resolving the hostname and rejecting reserved/private IP ranges.
class UrlValidator {

    private UrlValidator() {}

    // Throws IllegalArgumentException with a caller-safe message on any violation.
    // DNS rebinding caveat: the host is resolved once here at submission time; a malicious
    // DNS server could return a public IP now but a private IP when the worker actually
    // fetches it. Full mitigation requires pinning the resolved address in the HTTP client.
    static void validate(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL must not be blank");
        }

        // URI.create + toURL() is the non-deprecated path in Java 21 (new URL(String) is deprecated)
        URL parsed;
        try {
            parsed = URI.create(url.trim()).toURL();
        } catch (IllegalArgumentException | MalformedURLException e) {
            throw new IllegalArgumentException("Invalid URL format");
        }

        String protocol = parsed.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw new IllegalArgumentException("URL must use HTTP or HTTPS");
        }

        String host = parsed.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL must include a valid host");
        }

        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("URL host could not be resolved");
        }

        // Generic message intentional — saying "private IP blocked" would let an
        // attacker probe internal network topology by observing which hosts are reachable.
        if (isReserved(address)) {
            throw new IllegalArgumentException("URL is not allowed");
        }
    }

    private static boolean isReserved(InetAddress address) {
        return address.isLoopbackAddress()   // 127.0.0.0/8, ::1
            || address.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16, fc00::/7
            || address.isLinkLocalAddress()  // 169.254/16 (AWS metadata endpoint), fe80::/10
            || address.isAnyLocalAddress()   // 0.0.0.0
            || address.isMulticastAddress(); // 224.0.0.0/4, ff00::/8
    }
}
