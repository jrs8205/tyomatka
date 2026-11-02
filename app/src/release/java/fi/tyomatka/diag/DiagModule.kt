package fi.tyomatka.diag

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import okhttp3.Interceptor

/** Julkaisuversio: ei käynnistyskoukkuja eikä lisäinterceptoreita (diagnostiikkaloki on vain debug-buildissa). */
@Module
@InstallIn(SingletonComponent::class)
abstract class DiagModule {
    @Multibinds
    abstract fun startups(): Set<AppStartup>

    @Multibinds
    abstract fun interceptors(): Set<Interceptor>
}
