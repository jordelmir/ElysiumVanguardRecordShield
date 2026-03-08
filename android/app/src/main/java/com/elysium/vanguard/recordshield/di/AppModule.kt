package com.elysium.vanguard.recordshield.di

import android.content.Context
import androidx.room.Room
import com.elysium.vanguard.recordshield.BuildConfig
import com.elysium.vanguard.recordshield.data.local.ChunkDao
import com.elysium.vanguard.recordshield.data.local.RecordShieldDatabase
import com.elysium.vanguard.recordshield.data.local.RecordingDao
import com.elysium.vanguard.recordshield.data.remote.EvidenceApiClient
import com.elysium.vanguard.recordshield.data.repository.ChunkRepositoryImpl
import com.elysium.vanguard.recordshield.data.repository.EvidenceUploadRepositoryImpl
import com.elysium.vanguard.recordshield.data.repository.RecordingRepositoryImpl
import com.elysium.vanguard.recordshield.domain.repository.ChunkRepository
import com.elysium.vanguard.recordshield.domain.repository.EvidenceUploadRepository
import com.elysium.vanguard.recordshield.domain.repository.RecordingRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * ============================================================================
 * AppModule — Hilt Dependency Injection Configuration
 * ============================================================================
 *
 * Why SingletonComponent: Database, HTTP clients, and repositories must be
 * singletons. Creating multiple Room instances causes WAL lock contention.
 * Multiple Ktor clients waste connection pool resources.
 *
 * Why @Provides over @Binds: We need constructor parameters (Context, DB
 * instance) that @Binds can't handle. @Provides gives us full control over
 * object creation.
 * ============================================================================
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ========================================================================
    // DATABASE
    // ========================================================================

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RecordShieldDatabase {
        return Room.databaseBuilder(
            context,
            RecordShieldDatabase::class.java,
            "record_shield.db"
        )
        // Why fallbackToDestructiveMigration: During alpha/beta, we prioritize
        // development speed. In production, proper migrations will be added.
        .fallbackToDestructiveMigration()
        .build()
    }

    @Provides
    fun provideRecordingDao(db: RecordShieldDatabase): RecordingDao = db.recordingDao()

    @Provides
    fun provideChunkDao(db: RecordShieldDatabase): ChunkDao = db.chunkDao()

    // ========================================================================
    // NETWORK
    // ========================================================================

    @Provides
    @Singleton
    fun provideEvidenceApiClient(): EvidenceApiClient {
        return EvidenceApiClient(baseUrl = BuildConfig.VERCEL_API_URL)
    }

    // ========================================================================
    // REPOSITORIES
    // ========================================================================

    @Provides
    @Singleton
    fun provideRecordingRepository(recordingDao: RecordingDao): RecordingRepository {
        return RecordingRepositoryImpl(recordingDao)
    }

    @Provides
    @Singleton
    fun provideChunkRepository(
        chunkDao: ChunkDao,
        recordingDao: RecordingDao
    ): ChunkRepository {
        return ChunkRepositoryImpl(chunkDao, recordingDao)
    }

    @Provides
    @Singleton
    fun provideEvidenceUploadRepository(apiClient: EvidenceApiClient): EvidenceUploadRepository {
        return EvidenceUploadRepositoryImpl(apiClient)
    }
}
