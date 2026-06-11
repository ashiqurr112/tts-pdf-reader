package com.example.ttspdfreader.core.di

import android.content.Context
import com.example.ttspdfreader.data.tts.AudioRenderer
import com.example.ttspdfreader.data.tts.ModelManager
import com.example.ttspdfreader.data.tts.NeuTTSEngine
import com.example.ttspdfreader.data.tts.SentenceChunker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TtsModule {

    @Provides
    @Singleton
    fun provideModelManager(@ApplicationContext context: Context): ModelManager {
        return ModelManager(context)
    }

    @Provides
    @Singleton
    fun provideNeuTTSEngine(@ApplicationContext context: Context, modelManager: ModelManager): NeuTTSEngine {
        return NeuTTSEngine(context, modelManager)
    }

    @Provides
    @Singleton
    fun provideSentenceChunker(): SentenceChunker {
        return SentenceChunker()
    }

    @Provides
    @Singleton
    fun provideAudioRenderer(): AudioRenderer {
        return AudioRenderer()
    }
}
