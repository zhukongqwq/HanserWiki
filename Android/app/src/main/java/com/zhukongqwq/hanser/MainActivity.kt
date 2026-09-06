package com.zhukongqwq.hanser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.zhukongqwq.hanser.ui.ChatScreen
import com.zhukongqwq.hanser.ui.HanserTheme

/**
 * 主界面入口：初始化核心（数据目录/词典/索引）后挂载自绘聊天界面。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCore.init(this)
        AppCore.indexInBackground() // 启动后台增量索引
        setContent {
            HanserTheme {
                ChatScreen()
            }
        }
    }
}
