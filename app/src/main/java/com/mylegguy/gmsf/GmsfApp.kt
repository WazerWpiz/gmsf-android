package com.mylegguy.gmsf

import android.app.Application

class GmsfApp : Application() {

    lateinit var repository: SongRepository
        private set
    lateinit var audio: AudioEngine
        private set
    lateinit var sequencer: Sequencer
        private set

    override fun onCreate() {
        super.onCreate()
        AssetStore.init(this)
        repository = SongRepository(this)
        audio = AudioEngine(this)
        sequencer = Sequencer(audio)
        AssetStore.preloadCore()
        AppState.song = repository.loadLast() ?: Song()
        audio.preloadAllAsync()
    }
}
