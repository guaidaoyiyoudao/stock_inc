package com.stock.dividend.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 视觉模型配置的默认值与全局智谱 key 回退逻辑（Robolectric 真实 SharedPreferences）。 */
@RunWith(RobolectricTestRunner::class)
class LlmConfigRepositoryVisionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `vision snapshot falls back to global key when global llm is zhipu`() = runTest {
        val repo = LlmConfigRepository(context)
        repo.saveConfig(LlmConfig("https://open.bigmodel.cn/api/paas/v4/", "global-key", "glm-4-flash"))

        val vision = repo.visionSnapshot()

        assertThat(vision.apiKey).isEqualTo("global-key")
        assertThat(vision.baseUrl).isEqualTo(LlmConfigRepository.VISION_BASE_URL)
        assertThat(vision.model).isEqualTo(LlmConfigRepository.DEFAULT_VISION_MODEL)
        assertThat(vision.isComplete).isTrue()
    }

    @Test
    fun `no fallback when global llm is another provider`() = runTest {
        val repo = LlmConfigRepository(context)
        repo.saveConfig(LlmConfig("https://api.deepseek.com/v1/", "deepseek-key", "deepseek-v4-flash"))

        val vision = repo.visionSnapshot()

        assertThat(vision.apiKey).isEmpty()
        assertThat(vision.isComplete).isFalse()
    }

    @Test
    fun `explicit vision key and model win over fallback`() = runTest {
        val repo = LlmConfigRepository(context)
        repo.saveConfig(LlmConfig("https://open.bigmodel.cn/api/paas/v4/", "global-key", "glm-4-flash"))
        repo.saveVisionConfig(apiKey = "vision-key", model = "glm-4.6v")

        val vision = repo.visionSnapshot()

        assertThat(vision.apiKey).isEqualTo("vision-key")
        assertThat(vision.model).isEqualTo("glm-4.6v")
    }

    @Test
    fun `blank vision model falls back to default`() = runTest {
        val repo = LlmConfigRepository(context)
        repo.saveVisionConfig(apiKey = "vision-key", model = " ")

        val vision = repo.visionSnapshot()

        assertThat(vision.model).isEqualTo(LlmConfigRepository.DEFAULT_VISION_MODEL)
    }
}
