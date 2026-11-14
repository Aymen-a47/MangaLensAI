package com.mangalens.ai

import android.app.Application
import com.mangalens.ai.data.AppDb
import com.mangalens.ai.data.SettingsRepository

class MangaLensApp : Application() {
    val settings by lazy { SettingsRepository(this) }
    val db by lazy { AppDb.get(this) }
}
