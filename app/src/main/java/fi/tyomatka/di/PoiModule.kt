package fi.tyomatka.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fi.tyomatka.BuildConfig
import fi.tyomatka.core.api.PoiClient
import fi.tyomatka.core.api.RateLimiter
import fi.tyomatka.core.api.ReportClient
import fi.tyomatka.core.api.ValhallaEtaClient
import fi.tyomatka.core.api.ValhallaPreviewClient
import fi.tyomatka.data.repo.ServerUrls
import fi.tyomatka.nav.ferrostar.valhallaHttpClient
import okhttp3.OkHttpClient
import javax.inject.Singleton

/** Karttakohteiden palvelut: oma Worker (kohteet ja ilmoitukset) ja kohdekortin Valhalla-ETA (sama palvelin ja kuristin kuin ajossa). */
@Module
@InstallIn(SingletonComponent::class)
object PoiModule {
    @Provides
    @Singleton
    fun poiClient(okHttp: OkHttpClient, servers: ServerUrls): PoiClient = PoiClient(okHttp, servers.poi)

    @Provides
    @Singleton
    fun reportClient(okHttp: OkHttpClient, servers: ServerUrls): ReportClient = ReportClient(okHttp, servers.poi)

    @Provides
    @Singleton
    fun previewClient(okHttp: OkHttpClient, limiter: RateLimiter, servers: ServerUrls): ValhallaPreviewClient =
        ValhallaPreviewClient(valhallaHttpClient(okHttp, BuildConfig.VERSION_NAME), servers.valhalla, limiter)

    @Provides
    @Singleton
    fun etaClient(okHttp: OkHttpClient, limiter: RateLimiter, servers: ServerUrls): ValhallaEtaClient =
        ValhallaEtaClient(valhallaHttpClient(okHttp, BuildConfig.VERSION_NAME), servers.valhalla, limiter)
}
