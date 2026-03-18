package com.smart.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*

class MainViewModel : ViewModel() {


    // 响应式委托：用于监听变化，驱动 UI
    private val clickCountFlow by smartStorageStateFlow("click_count", 0)
    private val userProfileFlow by smartStorageStateFlow("user_profile", UserProfile(0, "Guest"))

    /**
     * 3. 聚合 UI State (MVI 模式)
     * 使用 combine 确保任何一个流发生变化，uiState 都会重新计算并推送到 Compose。
     */
    val uiState: StateFlow<MainUiState> = combine(
        userProfileFlow,
        clickCountFlow
    ) { profile, count ->
        MainUiState(
            nickname = profile.nickname,
            welcomeMessage = "欢迎回来, ${profile.nickname}!",
            currentCount = count
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState()
    )

    fun updateProfile(id: Int, nickname: String) {
        userProfileFlow.update { it.copy(id = id, nickname = nickname) }
    }

    fun incrementClick() {
        clickCountFlow.update { it + 1 }
    }
}

data class MainUiState(
    val nickname: String = "",
    val welcomeMessage: String = "",
    val currentCount: Int = 0
)
