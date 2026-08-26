package com.ivy.aiquota

/** 向手表端发送数据的通道抽象（由 WearBridgeService 基于 MessageApi 实现） */
interface WearChannel {
    fun send(json: String)
    fun isReady(): Boolean
}