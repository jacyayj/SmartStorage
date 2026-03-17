package com.smart.storage

import android.app.Application
import android.os.StrictMode

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SmartStorage.init(this)
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()  // 检测主线程读
                .detectDiskWrites() // 检测主线程写
                .detectAll()
                .penaltyLog()       // 违规时打印 logcat
                .penaltyFlashScreen() // 违规时屏幕闪烁
                .build()
        )
    }
}
