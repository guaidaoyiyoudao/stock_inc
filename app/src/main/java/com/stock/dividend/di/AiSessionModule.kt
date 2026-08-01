package com.stock.dividend.di

import android.content.Context
import com.google.adk.kt.sessions.SessionService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** AI 会话持久化：ADK RoomSessionService（独立 DB，不动 App 主库 schema）。 */
@Module
@InstallIn(SingletonComponent::class)
object AiSessionModule {
    private const val AI_SESSIONS_DB = "ai_sessions.db"

    @Provides
    @Singleton
    fun provideAiSessionService(@ApplicationContext context: Context): SessionService =
        createRoomSessionService(context)

    /**
     * ADK 0.6.0 发布的 RoomSessionService 在 Kotlin 2.1 编译器下无法直接引用
     * （AAR 内类存在、字节码与元数据均 public，但仅此类解析失败），故用反射调用官方
     * `fromContext`；返回对象以公共接口 [SessionService] 使用。R8 规则见 proguard-rules.pro。
     */
    private fun createRoomSessionService(context: Context): SessionService {
        // fromContext 定义在 companion object 且无 @JvmStatic：外层类的静态 Companion 字段持有单例
        val outerClass = Class.forName("com.google.adk.kt.sessions.room.RoomSessionService")
        val companion = outerClass.getField("Companion").get(null)
        val companionClass = Class.forName("com.google.adk.kt.sessions.room.RoomSessionService\$Companion")
        val method = companionClass.getMethod("fromContext", Context::class.java, String::class.java)
        return method.invoke(companion, context.applicationContext, AI_SESSIONS_DB) as SessionService
    }
}
