package com.fridge.board

import android.app.Application

class BoardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BoardStore.init(this)
    }
}
