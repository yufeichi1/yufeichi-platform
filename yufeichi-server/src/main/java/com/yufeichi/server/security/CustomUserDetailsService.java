package com.yufeichi.server.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yufeichi.server.entity.User;
import com.yufeichi.server.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserMapper userMapper;

    @Override
    public UserDetails loadUserByUsername(String username)
            throws UsernameNotFoundException {
        User user = userMapper.selectOne(
                Wrappers.<User>lambdaQuery()
                        .eq(User::getUsername, username)
                        .last("LIMIT 1")
        );

        return buildLoginUser(user);
    }

    public LoginUser loadUserById(Long userId)
            throws UsernameNotFoundException {
        User user = userMapper.selectById(userId);
        return buildLoginUser(user);
    }

    private LoginUser buildLoginUser(User user) {
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在");
        }

        List<String> roles =
                userMapper.selectRoleCodesByUserId(user.getId());

        List<String> permissions =
                userMapper.selectPermissionCodesByUserId(user.getId());

        return new LoginUser(user, roles, permissions);
    }
}
