package com.smartcity.traffic.dto;

/**
 * 登录/刷新成功后返回的令牌信息。
 */
public class LoginResponse {

    /** 访问令牌，放请求头 Authorization: Bearer xxx */
    private String accessToken;

    /** 刷新令牌，用于 accessToken 过期后换新 */
    private String refreshToken;

    /** 令牌类型，固定 Bearer */
    private String tokenType = "Bearer";

    /** 访问令牌有效期（秒） */
    private long expiresIn;

    public LoginResponse() {
    }

    public LoginResponse(String accessToken, String refreshToken, long expiresIn) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresIn = expiresIn;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public long getExpiresIn() {
        return expiresIn;
    }

    public void setExpiresIn(long expiresIn) {
        this.expiresIn = expiresIn;
    }
}
