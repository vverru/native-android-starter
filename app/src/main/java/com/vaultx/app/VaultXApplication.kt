package com.vaultx.app

import android.app.Application
import com.vaultx.app.data.db.VaultDatabaseHelper
import com.vaultx.app.data.telegram.TelegramClientManager

class VaultXApplication : Application() {

    lateinit var databaseHelper: VaultDatabaseHelper
        private set

    lateinit var telegramClient: TelegramClientManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        databaseHelper = VaultDatabaseHelper(this)
        telegramClient = TelegramClientManager(this)

        // Purge any old dummy sample data so only real user files are shown
        databaseHelper.purgeDummyData()
    }

    companion object {
        lateinit var instance: VaultXApplication
            private set
    }
}
