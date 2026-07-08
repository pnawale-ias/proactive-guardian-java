package com.proactiveguardian.web;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/** Port of {@code tests/test_e2e_webhook.py::test_webhook_rejects_bad_hmac}. */
class GithubHmacFilterTest {

    @Test
    void verifiesCorrectSignature() throws Exception {
        String secret = "shhh";
        byte[] body = "{\"hello\":\"world\"}".getBytes();
        String expected = sign(secret, body);
        assertThat(GithubHmacFilter.verify(secret, body, expected)).isTrue();
    }

    @Test
    void rejectsBadSignature() {
        assertThat(GithubHmacFilter.verify("shhh", "x".getBytes(), "sha256=deadbeef")).isFalse();
    }

    @Test
    void rejectsMissingSecret() {
        assertThat(GithubHmacFilter.verify("", "x".getBytes(), "sha256=xxx")).isFalse();
        assertThat(GithubHmacFilter.verify(null, "x".getBytes(), "sha256=xxx")).isFalse();
    }

    @Test
    void rejectsMissingSignature() {
        assertThat(GithubHmacFilter.verify("shhh", "x".getBytes(), null)).isFalse();
    }

    private static String sign(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}

