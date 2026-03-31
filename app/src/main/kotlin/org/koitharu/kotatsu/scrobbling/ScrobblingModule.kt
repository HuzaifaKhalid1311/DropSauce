package org.haziffe.dropsauce.scrobbling

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import okhttp3.OkHttpClient
import org.haziffe.dropsauce.BuildConfig
import org.haziffe.dropsauce.core.db.MangaDatabase
import org.haziffe.dropsauce.core.network.BaseHttpClient
import org.haziffe.dropsauce.core.network.CurlLoggingInterceptor
import org.haziffe.dropsauce.scrobbling.anilist.data.AniListAuthenticator
import org.haziffe.dropsauce.scrobbling.anilist.data.AniListInterceptor
import org.haziffe.dropsauce.scrobbling.anilist.domain.AniListScrobbler
import org.haziffe.dropsauce.scrobbling.common.data.ScrobblerStorage
import org.haziffe.dropsauce.scrobbling.common.domain.Scrobbler
import org.haziffe.dropsauce.scrobbling.common.domain.model.ScrobblerService
import org.haziffe.dropsauce.scrobbling.common.domain.model.ScrobblerType
import org.haziffe.dropsauce.scrobbling.kitsu.data.KitsuAuthenticator
import org.haziffe.dropsauce.scrobbling.kitsu.data.KitsuInterceptor
import org.haziffe.dropsauce.scrobbling.kitsu.data.KitsuRepository
import org.haziffe.dropsauce.scrobbling.kitsu.domain.KitsuScrobbler
import org.haziffe.dropsauce.scrobbling.mal.data.MALAuthenticator
import org.haziffe.dropsauce.scrobbling.mal.data.MALInterceptor
import org.haziffe.dropsauce.scrobbling.mal.domain.MALScrobbler
import org.haziffe.dropsauce.scrobbling.shikimori.data.ShikimoriAuthenticator
import org.haziffe.dropsauce.scrobbling.shikimori.data.ShikimoriInterceptor
import org.haziffe.dropsauce.scrobbling.shikimori.domain.ShikimoriScrobbler
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ScrobblingModule {

	@Provides
	@Singleton
	@ScrobblerType(ScrobblerService.SHIKIMORI)
	fun provideShikimoriHttpClient(
		@BaseHttpClient baseHttpClient: OkHttpClient,
		authenticator: ShikimoriAuthenticator,
		@ScrobblerType(ScrobblerService.SHIKIMORI) storage: ScrobblerStorage,
	): OkHttpClient = baseHttpClient.newBuilder().apply {
		authenticator(authenticator)
		addInterceptor(ShikimoriInterceptor(storage))
	}.build()

	@Provides
	@Singleton
	@ScrobblerType(ScrobblerService.MAL)
	fun provideMALHttpClient(
		@BaseHttpClient baseHttpClient: OkHttpClient,
		authenticator: MALAuthenticator,
		@ScrobblerType(ScrobblerService.MAL) storage: ScrobblerStorage,
	): OkHttpClient = baseHttpClient.newBuilder().apply {
		authenticator(authenticator)
		addInterceptor(MALInterceptor(storage))
	}.build()

	@Provides
	@Singleton
	@ScrobblerType(ScrobblerService.ANILIST)
	fun provideAniListHttpClient(
		@BaseHttpClient baseHttpClient: OkHttpClient,
		authenticator: AniListAuthenticator,
		@ScrobblerType(ScrobblerService.ANILIST) storage: ScrobblerStorage,
	): OkHttpClient = baseHttpClient.newBuilder().apply {
		authenticator(authenticator)
		addInterceptor(AniListInterceptor(storage))
	}.build()

	@Provides
	@Singleton
	fun provideKitsuRepository(
		@ApplicationContext context: Context,
		@ScrobblerType(ScrobblerService.KITSU) storage: ScrobblerStorage,
		database: MangaDatabase,
		authenticator: KitsuAuthenticator,
	): KitsuRepository {
		val okHttp = OkHttpClient.Builder().apply {
			authenticator(authenticator)
			addInterceptor(KitsuInterceptor(storage))
			if (BuildConfig.DEBUG) {
				addInterceptor(CurlLoggingInterceptor())
			}
		}.build()
		return KitsuRepository(context, okHttp, storage, database)
	}

	@Provides
	@Singleton
	@ScrobblerType(ScrobblerService.ANILIST)
	fun provideAniListStorage(
		@ApplicationContext context: Context,
	): ScrobblerStorage = ScrobblerStorage(context, ScrobblerService.ANILIST)

	@Provides
	@Singleton
	@ScrobblerType(ScrobblerService.SHIKIMORI)
	fun provideShikimoriStorage(
		@ApplicationContext context: Context,
	): ScrobblerStorage = ScrobblerStorage(context, ScrobblerService.SHIKIMORI)

	@Provides
	@Singleton
	@ScrobblerType(ScrobblerService.MAL)
	fun provideMALStorage(
		@ApplicationContext context: Context,
	): ScrobblerStorage = ScrobblerStorage(context, ScrobblerService.MAL)

	@Provides
	@Singleton
	@ScrobblerType(ScrobblerService.KITSU)
	fun provideKitsuStorage(
		@ApplicationContext context: Context,
	): ScrobblerStorage = ScrobblerStorage(context, ScrobblerService.KITSU)

	@Provides
	@ElementsIntoSet
	fun provideScrobblers(
		shikimoriScrobbler: ShikimoriScrobbler,
		aniListScrobbler: AniListScrobbler,
		malScrobbler: MALScrobbler,
		kitsuScrobbler: KitsuScrobbler
	): Set<@JvmSuppressWildcards Scrobbler> = setOf(shikimoriScrobbler, aniListScrobbler, malScrobbler, kitsuScrobbler)
}
