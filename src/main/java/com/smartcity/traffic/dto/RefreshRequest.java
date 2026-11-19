package com.smartcity.traffic.dto;

/**
 * 刷新令牌请求。
 */
public class RefreshRequest {

    private String refreshToken;

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}
