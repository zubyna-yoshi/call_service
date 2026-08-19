package com.company.callservice

import android.app.Application
import android.content.Context
import com.company.callservice.data.DirectoryRepository
import com.company.callservice.data.DirectorySnapshotStore
import com.company.callservice.network.DirectoryApiClient
import com.company.callservice.settings.SecretStore
import com.company.callservice.settings.SettingsStore

class DirectoryApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val settingsStore = SettingsStore(appContext)
    val secretStore = SecretStore(appContext)
    val snapshotStore = DirectorySnapshotStore(appContext)
    val directoryRepository = DirectoryRepository(
        context = appContext,
        settingsStore = settingsStore,
        secretStore = secretStore,
        snapshotStore = snapshotStore,
        apiClient = DirectoryApiClient(),
    )
}

val Context.directoryGraph: AppGraph
    get() = (applicationContext as DirectoryApplication).graph
