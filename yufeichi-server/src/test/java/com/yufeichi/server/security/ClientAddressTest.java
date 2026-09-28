package com.yufeichi.server.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class ClientAddressTest {
    @Test void onlyExplicitTrustedPeerCanSupplySingleNumericRealIp() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Real-IP", "198.51.100.1");
        request.addHeader("X-Forwarded-For", "203.0.113.99");
        assertThat(new ClientAddress("").resolve(request)).isEqualTo("192.0.2.10");
        assertThat(new ClientAddress("192.0.2.10").resolve(request)).isEqualTo("198.51.100.1");
        for (String invalid : new String[]{"evil.example", "198.51.100.1, 198.51.100.2", "not-an-ip", "999.2.3.4"}) {
            request.removeHeader("X-Real-IP"); request.addHeader("X-Real-IP", invalid);
            assertThat(new ClientAddress("192.0.2.10").resolve(request)).isEqualTo("192.0.2.10");
        }
    }
}
