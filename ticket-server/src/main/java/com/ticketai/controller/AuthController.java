package com.ticketai.controller;

import com.ticketai.common.Result;
import com.ticketai.dto.LoginDTO;
import com.ticketai.dto.RefreshDTO;
import com.ticketai.service.AuthService;
import com.ticketai.vo.LoginVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "认证")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    @Operation(summary = "登录，返回 accessToken + refreshToken")
    public Result<LoginVO> login(@RequestBody @Valid LoginDTO dto) {
        return Result.ok(authService.login(dto));
    }

    @PostMapping("/refresh")
    @Operation(summary = "刷新 token（旋转）")
    public Result<LoginVO> refresh(@RequestBody @Valid RefreshDTO dto) {
        return Result.ok(authService.refresh(dto));
    }

    @PostMapping("/logout")
    @Operation(summary = "登出：吊销 access token + 删除 refresh token（幂等）")
    public Result<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) RefreshDTO dto) {
        authService.logout(authorization, dto);
        return Result.ok(null);
    }
}
