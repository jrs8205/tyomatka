package fi.tyomatka.diag

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import okhttp3.Interceptor

/** Debug-build: diagnostiikkalokin käynnistys ja verkkorivit. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DiagModule {
    @Binds
    @IntoSet
    abstract fun diagStartup(impl: DiagStartup): AppStartup

    @Multibinds
    abstract fun interceptors(): Set<Interceptor>

    companion object {
        @Provides
        @IntoSet
        fun diagHttp(): Interceptor = DiagHttpInterceptor()
    }
}
