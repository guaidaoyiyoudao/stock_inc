package com.stock.dividend

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.stock.dividend.data.notification.EXTRA_STOCK_CODE
import com.stock.dividend.ui.navigation.AppNavigation
import com.stock.dividend.ui.theme.StockDividendTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** 待消费的 deep link stockCode；null 表无。通知点击时写入，MainScaffold 消费后置 null。 */
    var pendingDeepLink by mutableStateOf<String?>(null)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeDeepLink(intent)   // 冷启动：从启动 Intent 取 stockCode
        setContent {
            StockDividendTheme {
                // 根级不透明背景：外层 NavHost 转场（生活支出/网格交易等 rootNavController
                // 目的地）滑动+fade 中点两页均半透明，若无背景会透出 XML 主题恒白的
                // windowBackground，夜间模式下表现为切页白闪。内层 Tab NavHost 已由
                // MainScaffold 的 Scaffold 垫底，此处为外层补齐同一层。
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(
                        pendingDeepLink = pendingDeepLink,
                        onDeepLinkConsumed = { pendingDeepLink = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeDeepLink(intent)   // 热启动：通知点击且 App 已在前台
    }

    private fun consumeDeepLink(intent: Intent?) {
        pendingDeepLink = intent?.getStringExtra(EXTRA_STOCK_CODE)
    }
}
