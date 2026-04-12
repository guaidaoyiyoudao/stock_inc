package com.stock.dividend.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ToUserMessageTest {

    @Test
    fun `SocketTimeoutException returns timeout message`() {
        val exception = SocketTimeoutException("timeout")
        assertThat(exception.toUserMessage()).isEqualTo("网络连接超时，请重试")
    }

    @Test
    fun `UnknownHostException returns no network message`() {
        val exception = UnknownHostException("no host")
        assertThat(exception.toUserMessage()).isEqualTo("网络连接失败，请检查网络后重试")
    }

    @Test
    fun `ConnectException returns no network message`() {
        val exception = ConnectException("refused")
        assertThat(exception.toUserMessage()).isEqualTo("网络连接失败，请检查网络后重试")
    }

    @Test
    fun `HttpException 5xx returns server error message`() {
        val exception = HttpException(Response.error<Any>(500, okhttp3.ResponseBody.create(null, "")))
        assertThat(exception.toUserMessage()).isEqualTo("服务器暂时无法响应，请稍后重试")
    }

    @Test
    fun `HttpException 4xx returns generic failure message`() {
        val exception = HttpException(Response.error<Any>(404, okhttp3.ResponseBody.create(null, "")))
        assertThat(exception.toUserMessage()).isEqualTo("网络请求失败，请重试")
    }

    @Test
    fun `generic exception returns generic failure message`() {
        val exception = RuntimeException("something went wrong")
        assertThat(exception.toUserMessage()).isEqualTo("操作失败，请重试")
    }
}
