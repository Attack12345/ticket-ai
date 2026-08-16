package com.ticketai.service;

import com.ticketai.dto.LoginDTO;
import com.ticketai.dto.RefreshDTO;
import com.ticketai.vo.LoginVO;

public interface AuthService {

    LoginVO login(LoginDTO dto);

    LoginVO refresh(RefreshDTO dto);
}
