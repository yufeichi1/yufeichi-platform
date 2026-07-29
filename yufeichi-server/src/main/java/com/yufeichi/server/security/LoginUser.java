package com.yufeichi.server.security;

import com.yufeichi.server.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

@Getter
public class LoginUser implements UserDetails {

    private final User user;
    private final List<String> roles;
    private final List<String> permissions;

    public LoginUser(
            User user,
            List<String> roles,
            List<String> permissions
    ) {
        this.user = user;
        this.roles = roles == null ? List.of() : List.copyOf(roles);
        this.permissions = permissions == null
                ? List.of()
                : List.copyOf(permissions);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        Stream<String> permissionAuthorities = permissions.stream();

        Stream<String> roleAuthorities = roles.stream()
                .map(role -> "ROLE_" + role.toUpperCase());

        return Stream.concat(
                        permissionAuthorities,
                        roleAuthorities
                )
                .distinct()
                .map(SimpleGrantedAuthority::new)
                .toList();
    }

    @Override
    public String getPassword() {
        return user.getPassword();
    }

    @Override
    public String getUsername() {
        return user.getUsername();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return Integer.valueOf(1).equals(user.getStatus());
    }
}
