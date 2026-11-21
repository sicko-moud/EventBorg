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
    val stories = Stories(app)
    val watch = WatchStore(app)
    val repo = Repo(app, { http }, scope) { api.stories()?.let(stories::remote) }
    val topics = Topics(prefs, watch.state, scope)
    val notifier = Notifier(app)
    val detector = Detector(this)
    val drafts = HashMap<String, Extract>()

    fun start() {
        repo.load()
        topics.start()
        scope.launch(Dispatchers.IO) {
            val listen = prefs.state.first { it.loaded }.listen
            runCatching { Listen.setEnabled(ctx, listen) }
            watch.prune()
        }
    }
}

val Context.graph: Graph get() = (applicationContext as App).graph
