package fi.tyomatka.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fi.tyomatka.data.repo.HistoryRepository
import fi.tyomatka.data.repo.RouteRepository
import fi.tyomatka.drive.AndroidDriveHost
import fi.tyomatka.drive.DriveHost
import fi.tyomatka.drive.DriveStore
import fi.tyomatka.drive.LearningStore

@Module
@InstallIn(SingletonComponent::class)
abstract class DriveModule {
    @Binds
    abstract fun driveStore(impl: RouteRepository): DriveStore

    @Binds
    abstract fun learningStore(impl: HistoryRepository): LearningStore

    @Binds
    abstract fun driveHost(impl: AndroidDriveHost): DriveHost
}
