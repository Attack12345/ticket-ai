package com.ticketai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RefreshDTO {

    @NotBlank(message = "refreshToken 不能为空")
    private String refreshToken;
}
