package com.paperlens.app

import android.app.Application
import android.content.Context
import com.paperlens.app.data.AppDatabase
import com.paperlens.app.data.DocumentRepository
import com.paperlens.app.data.SettingsStore
import com.paperlens.app.export.Exporter
import com.paperlens.app.imaging.CornerModel
import com.paperlens.app.ml.LanguageDownloads
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/** Application-wide service locator; the app is small enough not to need a DI framework. */
class PaperLensApp : Application() {

    lateinit var settings: SettingsStore
        private set
    lateinit var repository: DocumentRepository
        private set
    lateinit var exporter: Exporter
        private set

    override fun onCreate() {
        super.onCreate()
        S.init(this)
        PDFBoxResourceLoader.init(this)
        CornerModel.init(this)
        LanguageDownloads.init(this)
        settings = SettingsStore(this)
        repository = DocumentRepository(this, AppDatabase.create(this).documentDao(), settings)
        exporter = Exporter(this)
    }
}

val Context.app: PaperLensApp get() = applicationContext as PaperLensApp
