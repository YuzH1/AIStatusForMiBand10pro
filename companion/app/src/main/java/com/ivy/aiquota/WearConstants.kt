package com.ivy.aiquota

/**
 * 与手表端快应用 src/utils/api.js 严格对齐的协议常量。
 * 修改任何字段必须同步修改两端。
 */
object WearConstants {

    const val RPC_MARKER = "__rpc"
    const val EVENT_MARKER = "__event"

    const val FIELD_ID = "id"
    const val FIELD_METHOD = "method"
    const val FIELD_PARAMS = "params"
    const val FIELD_RESULT = "result"
    const val FIELD_ERROR = "error"
    const val FIELD_CODE = "code"
    const val FIELD_MSG = "msg"
    const val FIELD_EVENT = "event"
    const val FIELD_DATA = "data"

    object Method {
        const val QUOTA_LIST = "quota.list"
        const val QUOTA_REFRESH = "quota.refresh"
        const val CONN_TEST = "conn.test"
        const val CONFIG_GET = "config.get"
    }

    object Event {
        const val QUOTA_UPDATE = "update.quota"
        const val CONNECTION_STATE = "update.connectionState"
    }

    object Code {
        const val OK = 0
        const val PARSE_ERROR = -32700
        const val METHOD_NOT_FOUND = -32601
        const val INTERNAL_ERROR = -32603
        const val FETCH_FAILED = -1
    }
}