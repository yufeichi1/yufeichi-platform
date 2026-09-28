package com.yufeichi.server.service.impl;

import com.yufeichi.server.common.error.BusinessException;
import com.yufeichi.server.common.error.ErrorCode;
import com.yufeichi.server.dto.LoginDTO;
import com.yufeichi.server.entity.User;
import com.yufeichi.server.security.JwtTokenProvider;
import com.yufeichi.server.security.LoginUser;
import com.yufeichi.server.security.RedisSecurityStore;
import com.yufeichi.server.security.ClientAddress;
import com.yufeichi.server.security.AuditEvents;
import com.yufeichi.server.service.AuthService;
import com.yufeichi.server.vo.LoginVO;
import com.yufeichi.server.vo.UserInfoVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthServiceImpl implements AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final RedisSecurityStore securityStore;
    private final ClientAddress clientAddress;

    @Override
    public LoginVO login(LoginDTO loginDTO) {
        String username=RedisSecurityStore.normalizeUsername(loginDTO.getUsername());
        String ip=clientAddress.current();
        securityStore.checkLogin(username,ip);
        try {
            Authentication authentication =
                    authenticationManager.authenticate(
                            new UsernamePasswordAuthenticationToken(
                                    username,
                                    loginDTO.getPassword()
                            )
                    );

            LoginUser loginUser =
                    (LoginUser) authentication.getPrincipal();
            securityStore.successfulLogin(username);
            AuditEvents.record("login","success",loginUser.getUser().getId(),null);

            String token =
                    jwtTokenProvider.createToken(loginUser);

            return LoginVO.builder()
                    .token(token)
                    .tokenType("Bearer")
                    .expireMinutes(
                            jwtTokenProvider.getExpireMinutes()
                    )
                    .userInfo(toUserInfoVO(loginUser))
                    .permissions(loginUser.getPermissions())
                    .build();
        } catch (AuthenticationServiceException exception) {
            // Database/provider failures are server errors, not invalid passwords.
            throw exception;
        } catch (DisabledException exception) {
            securityStore.failedLogin(username,ip);
            AuditEvents.record("login","failure",null,null);
            throw new BusinessException(ErrorCode.USER_DISABLED);
        } catch (AuthenticationException exception) {
            securityStore.failedLogin(username,ip);
            AuditEvents.record("login","failure",null,null);
            throw new BusinessException(
                    ErrorCode.USERNAME_OR_PASSWORD_ERROR
            );
        }
    }

    @Override
    public UserInfoVO getCurrentUser() {
        Authentication authentication =
                SecurityContextHolder.getContext()
                        .getAuthentication();

        if (authentication == null
                || !(authentication.getPrincipal()
                instanceof LoginUser loginUser)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        return toUserInfoVO(loginUser);
    }

    @Override
    public void logout() {
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if(!(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet))
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        String authorization=servlet.getRequest().getHeader("Authorization");
        if(authorization==null || !authorization.regionMatches(true,0,"Bearer ",0,7))
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        String token=authorization.substring(7).trim();
        try { securityStore.revoke(token,jwtTokenProvider.getExpiresAt(token)); }
        catch(io.jsonwebtoken.ExpiredJwtException alreadyExpired) { /* Already unusable. */ }
        AuditEvents.record("logout","success",AuditEvents.actor(),null);
        SecurityContextHolder.clearContext();
    }

    private UserInfoVO toUserInfoVO(LoginUser loginUser) {
        User user = loginUser.getUser();

        return UserInfoVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .email(user.getEmail())
                .roles(loginUser.getRoles())
                .permissions(loginUser.getPermissions())
                .build();
    }
}
