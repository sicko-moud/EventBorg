package app.eventborg

import android.app.Application
import android.content.Context
import app.eventborg.data.Api
import app.eventborg.data.Extract
import app.eventborg.data.Prefs
import app.eventborg.data.Repo
import app.eventborg.data.Stories
import app.eventborg.data.Topics
import app.eventborg.notify.Detector
import app.eventborg.notify.Listen
import app.eventborg.notify.Notifier
import app.eventborg.watch.WatchStore
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    private val ctx: Context = app
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
    val api = Api { http }
    // Stores that read a file on creation are opened on IO in start(); a caller that gets there first just waits for it.
    val stories by lazy { Stories(app) }
    val watch by lazy { WatchStore(app) }
    val repo = Repo(app, { http }, scope) { api.stories()?.let(stories::remote) }
    val topics by lazy { Topics(prefs, watch.state, repo.data.map { d -> d.sources.filter { it.pri }.map { it.id }.toSet() }, scope) }
    val notifier by lazy { Notifier(app) }
    val detector by lazy { Detector(this) }
    val drafts = HashMap<String, Extract>()

    fun start() {
        repo.load()
        scope.launch(Dispatchers.IO) {
            stories
            topics.start()
            val listen = prefs.state.first { it.loaded }.listen
            runCatching { Listen.setEnabled(ctx, listen) }
            watch.prune()
        }
        scope.launch(Dispatchers.IO) { repo.data.collect { if (it.sources.isNotEmpty()) watch.seed(it.sources) } }
    }
}

val Context.graph: Graph get() = (applicationContext as App).graph
