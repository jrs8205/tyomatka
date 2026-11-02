package fi.tyomatka.di

import com.stadiamaps.ferrostar.core.NavigationViewModel
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fi.tyomatka.nav.NavigationEngine
import fi.tyomatka.nav.ferrostar.FerrostarNavigationEngine

/** Ajosession moottori ja ajokartan näkymätila ovat sama singleton. */
@Module
@InstallIn(SingletonComponent::class)
abstract class NavigationModule {
    @Binds
    abstract fun navigationEngine(impl: FerrostarNavigationEngine): NavigationEngine

    @Binds
    abstract fun navigationViewModel(impl: FerrostarNavigationEngine): NavigationViewModel
}
