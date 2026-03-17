package com.smart.storage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smart.storage.ui.theme.SmartStorageTheme
import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(val id: Int, val nickname: String = "Guest")

class MainActivity : ComponentActivity() {

    // 可以在 Activity 中直接使用同步读写委托
    private var lastOpenTime by smartStorage("last_open_time", "")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastOpenTime = java.util.Date().toString()
        enableEdgeToEdge()
        setContent {
            SmartStorageTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SampleContent(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun SampleContent(modifier: Modifier = Modifier, vm: MainViewModel = viewModel()) {
    // 观察来自 ViewModel 的响应式 StateFlow
    val uiState by vm.uiState.collectAsState()

    Column(modifier = modifier.padding(16.dp)) {
        Text(text = "SmartStorage MVI 示例", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        Text(text = "当前昵称: ${uiState.nickname}", style = MaterialTheme.typography.titleLarge)
        Text(text = uiState.welcomeMessage)
        Text(text = "点击计数 (磁盘同步): ${uiState.currentCount}")

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = {
            vm.updateProfile((0..1000).random(), "SmartUser_${(1..100).random()}")
        }) {
            Text("随机更新用户信息 (StateFlow)")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            vm.incrementClick()
        }) {
            Text("增加点击计数 (同步委托)")
        }
    }
}
