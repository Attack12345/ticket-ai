package com.ticketai.service;

import com.ticketai.dto.LoginDTO;
import com.ticketai.dto.RefreshDTO;
import com.ticketai.vo.LoginVO;

public interface AuthService {

    LoginVO login(LoginDTO dto);

    LoginVO refresh(RefreshDTO dto);

    /**
     * 登出：吊销 access token（jti 黑名单）+ 删除 refresh token（哈希比对）。
     * 幂等：token 已失效/已过期/缺失均视为成功。
     */
    void logout(String authorization, RefreshDTO dto);
}
