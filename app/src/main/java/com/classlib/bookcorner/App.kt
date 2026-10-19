package com.classlib.bookcorner

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import com.classlib.bookcorner.db.DbHelper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        helper = DbHelper(this)
    }

    companion object {
        lateinit var instance: App
            private set

        private lateinit var helper: DbHelper

        val db: SQLiteDatabase
            get() = helper.writableDatabase

        /** 所有数据库操作走这里，避免主线程 IO */
        val io: ExecutorService = Executors.newSingleThreadExecutor()

        val main = Handler(Looper.getMainLooper())

        fun ui(block: () -> Unit) {
            main.post(block)
        }
    }
}
