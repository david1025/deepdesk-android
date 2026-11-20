package com.david.deepdesk.feature.voice.asr

import com.david.deepdesk.core.common.AppError
import org.junit.Assert.assertEquals
import org.junit.Test

class AsrHintTest {

    @Test
    fun `API no audio 映射为未识别到内容`() {
        val err = AppError(
            AppError.Kind.UNKNOWN,
            "Invalid audio: no audio (request_id: fa4c6a6c-d2a2-40a2-9e7e-305eff32e463)",
        )
        assertEquals("未识别到内容", AsrExecutor.userFacingHint(err))
    }

    @Test
    fun `本地空文件映射为未识别到内容`() {
        val err = AppError.local("录音文件为空")
        assertEquals("未识别到内容", AsrExecutor.userFacingHint(err))
    }

    @Test
    fun `其它错误保留原文`() {
        val err = AppError.network("网络连接不可用")
        assertEquals("网络连接不可用", AsrExecutor.userFacingHint(err))
    }
}
