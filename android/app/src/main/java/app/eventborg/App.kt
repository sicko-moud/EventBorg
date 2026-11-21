package app.eventborg

import android.app.Application
import android.content.Context
import app.eventborg.data.Api
import app.eventborg.data.Prefs
import app.eventborg.data.Repo
import app.eventborg.data.Topics
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application(), SingletonImageLoader.Factory {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        graph.start()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.http })) }
            .crossfade(true)
            .build()
}

class Graph(app: App) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", "EventBorg/${BuildConfig.VERSION_NAME} Android").build())
            }
            .build()
    }
    val prefs = Prefs(app, scope)
    val repo = Repo(app, { http }, scope)
    val api = Api { http }
    private val topics = Topics(prefs, scope)

    fun start() {
        repo.load()
        topics.start()
    }
}

val Context.graph: Graph get() = (applicationContext as App).graph
