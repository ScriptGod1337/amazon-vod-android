package com.scriptgod.fireos.avod.model

import com.google.gson.annotations.SerializedName

data class TokenData(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("expires_in") val expiresIn: Long = 3600,
    @SerializedName("expires_at") val expiresAt: Long = 0L
)

data class TokenRefreshResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("expires_in") val expiresIn: Long
)
