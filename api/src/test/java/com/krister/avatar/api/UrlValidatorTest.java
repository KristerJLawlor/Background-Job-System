package com.krister.avatar.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

class UrlValidatorTest {

    // --- format checks (no DNS lookup occurs) ---

    @Test
    void nullUrl_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UrlValidator.validate(null))
                .withMessage("URL must not be blank");
    }

    @Test
    void blankUrl_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UrlValidator.validate("   "))
                .withMessage("URL must not be blank");
    }

    @Test
    void malformedUrl_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UrlValidator.validate("not a url"))
                .withMessage("Invalid URL format");
    }

    @Test
    void nonHttpProtocol_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UrlValidator.validate("ftp://files.example.com/file.png"))
                .withMessage("URL must use HTTP or HTTPS");
    }

    // --- SSRF guard (IP literals — no DNS lookup) ---

    @Test
    void loopbackIp_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UrlValidator.validate("http://127.0.0.1/image.png"))
                .withMessage("URL is not allowed");
    }

    @Test
    void privateClassAIp_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UrlValidator.validate("http://10.0.0.1/image.png"))
                .withMessage("URL is not allowed");
    }

    @Test
    void privateClassBIp_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UrlValidator.validate("http://172.16.0.1/image.png"))
                .withMessage("URL is not allowed");
    }

    @Test
    void privateClassCIp_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UrlValidator.validate("http://192.168.1.1/image.png"))
                .withMessage("URL is not allowed");
    }

    @Test
    void linkLocalIp_throws() {
        // 169.254.x.x — includes the AWS EC2 metadata endpoint
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UrlValidator.validate("http://169.254.169.254/latest/meta-data/"))
                .withMessage("URL is not allowed");
    }

    @Test
    void multicastIp_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UrlValidator.validate("http://224.0.0.1/image.png"))
                .withMessage("URL is not allowed");
    }

    @Test
    void ipv6Loopback_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UrlValidator.validate("http://[::1]/image.png"))
                .withMessage("URL is not allowed");
    }

    // --- valid public IP (no DNS lookup — IP literal passes SSRF guard) ---

    @Test
    void publicIpUrl_passes() {
        // 1.1.1.1 is a well-known public address; InetAddress parses IP literals without DNS
        assertThatNoException()
                .isThrownBy(() -> UrlValidator.validate("https://1.1.1.1/image.png"));
    }
}
