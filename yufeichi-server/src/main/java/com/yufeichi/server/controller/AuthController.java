package com.yufeichi.server.controller;

import com.yufeichi.server.common.result.Result;
import com.yufeichi.server.dto.LoginDTO;
import com.yufeichi.server.service.AuthService;
import com.yufeichi.server.vo.LoginVO;
import com.yufeichi.server.vo.UserInfoVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "登录认证")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "管理员登录")
    @PostMapping("/login")
    public Result<LoginVO> login(
            @Valid @RequestBody LoginDTO loginDTO
    ) {
        return Result.success(authService.login(loginDTO));
    }

    @Operation(
            summary = "获取当前用户",
            security = @SecurityRequirement(name = "BearerAuth")
    )
    @GetMapping("/me")
    public Result<UserInfoVO> currentUser() {
        return Result.success(authService.getCurrentUser());
    }

    @Operation(
            summary = "退出登录",
            security = @SecurityRequirement(name = "BearerAuth")
    )
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.success();
    }
}
