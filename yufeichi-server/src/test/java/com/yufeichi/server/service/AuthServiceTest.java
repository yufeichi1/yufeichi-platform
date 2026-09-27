package com.yufeichi.server.service;

import com.yufeichi.server.dto.LoginDTO;
import com.yufeichi.server.security.JwtTokenProvider;
import com.yufeichi.server.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.InternalAuthenticationServiceException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    @Test
    void providerInfrastructureFailureIsNotConvertedToBadPassword() {
        var manager = mock(AuthenticationManager.class);
        var failure = new InternalAuthenticationServiceException("test database unavailable");
        when(manager.authenticate(any())).thenThrow(failure);
        var service = new AuthServiceImpl(manager, mock(JwtTokenProvider.class));
        var dto = new LoginDTO();
        dto.setUsername("test-user");
        dto.setPassword("irrelevant");
        assertThatThrownBy(() -> service.login(dto)).isSameAs(failure);
    }
}
