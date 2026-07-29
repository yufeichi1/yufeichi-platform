package com.yufeichi.server.service.impl;

import com.yufeichi.server.common.error.BusinessException;
import com.yufeichi.server.common.error.ErrorCode;
import com.yufeichi.server.dto.LoginDTO;
import com.yufeichi.server.entity.User;
import com.yufeichi.server.security.JwtTokenProvider;
import com.yufeichi.server.security.LoginUser;
import com.yufeichi.server.service.AuthService;
import com.yufeichi.server.vo.LoginVO;
import com.yufeichi.server.vo.UserInfoVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
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

    @Override
    public LoginVO login(LoginDTO loginDTO) {
        try {
            Authentication authentication =
                    authenticationManager.authenticate(
                            new UsernamePasswordAuthenticationToken(
                                    loginDTO.getUsername(),
                                    loginDTO.getPassword()
                            )
                    );

            LoginUser loginUser =
                    (LoginUser) authentication.getPrincipal();

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
        } catch (DisabledException exception) {
            throw new BusinessException(ErrorCode.USER_DISABLED);
        } catch (AuthenticationException exception) {
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
        /*
         * 当前采用无状态 JWT。
         * 后端清理本次请求的 SecurityContext，
         * 前端必须同时删除本地 token。
         *
         * 第十七阶段可使用 Redis 黑名单实现 token 主动失效。
         */
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
                .build();
    }
}
