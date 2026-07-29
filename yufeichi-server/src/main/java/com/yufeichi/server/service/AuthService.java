package com.yufeichi.server.service;

import com.yufeichi.server.dto.LoginDTO;
import com.yufeichi.server.vo.LoginVO;
import com.yufeichi.server.vo.UserInfoVO;

public interface AuthService {

    LoginVO login(LoginDTO loginDTO);

    UserInfoVO getCurrentUser();

    void logout();
}
