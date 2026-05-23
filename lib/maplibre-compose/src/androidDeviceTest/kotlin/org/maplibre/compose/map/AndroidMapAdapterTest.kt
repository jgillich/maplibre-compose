package org.maplibre.compose.map

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runAndroidComposeUiTest
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.Style
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalTestApi::class)
class AndroidMapAdapterTest {
  @Test
  fun shouldIgnoreStyleLoadedAfterNewerStyleWasRequested() =
    runAndroidComposeUiTest<ComponentActivity> {
      val mapReady = CountDownLatch(1)
      val callbacks = StyleChangeCallbacks()
      val delayedStyleServer = DelayedStyleServer(emptyStyleJson("superseded"), delayMillis = 800)
      lateinit var mapView: MapView
      lateinit var adapter: AndroidMapAdapter

      runOnUiThread {
        MapLibre.getInstance(activity!!)
        mapView = MapView(activity!!)
        activity!!.setContentView(mapView)
        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()
        mapView.getMapAsync { map ->
          adapter =
            AndroidMapAdapter(
              mapView = mapView,
              map = map,
              scaleBar = AndroidScaleBar(activity!!, mapView, map),
              layoutDir = LayoutDirection.Ltr,
              density = density,
              callbacks = callbacks,
              logger = null,
              baseStyle = BaseStyle.Json(emptyStyleJson("initial")),
            )
          mapReady.countDown()
        }
      }

      assertTrue(mapReady.await(10, TimeUnit.SECONDS), "Map did not become ready")
      callbacks.awaitStyleLoaded("Initial style did not load")

      runOnUiThread {
        adapter.setBaseStyle(BaseStyle.Uri(delayedStyleServer.url))
        adapter.setBaseStyle(BaseStyle.Json(emptyStyleJson("newer")))
      }

      try {
        callbacks.awaitStyleLoaded("Newer style did not load")
        callbacks.assertNoStyleLoaded("Superseded style callback should have been ignored")
      } finally {
        delayedStyleServer.close()
        runOnUiThread {
          mapView.onPause()
          mapView.onStop()
          mapView.onDestroy()
        }
      }
    }

  private class StyleChangeCallbacks : MapAdapter.Callbacks {
    private val loadedStyles = LinkedBlockingQueue<Unit>()

    override fun onStyleChanged(map: MapAdapter, style: Style?) {
      if (style != null) loadedStyles.add(Unit)
    }

    fun awaitStyleLoaded(message: String) {
      assertNotNull(loadedStyles.poll(10, TimeUnit.SECONDS), message)
    }

    fun assertNoStyleLoaded(message: String) {
      assertNull(loadedStyles.poll(1, TimeUnit.SECONDS), message)
    }

    override fun onMapFinishedLoading(map: MapAdapter) {}

    override fun onMapFailLoading(reason: String?) {}

    override fun onCameraMoveStarted(map: MapAdapter, reason: CameraMoveReason) {}

    override fun onCameraMoved(map: MapAdapter) {}

    override fun onCameraMoveEnded(map: MapAdapter) {}

    override fun onClick(map: MapAdapter, latLng: Position, offset: DpOffset) {}

    override fun onLongClick(map: MapAdapter, latLng: Position, offset: DpOffset) {}

    override fun onFrame(fps: Double) {}
  }

  private class DelayedStyleServer(private val styleJson: String, private val delayMillis: Long) :
    AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${server.localPort}/style.json"
    private val thread =
      Thread {
          try {
            handle(server.accept())
          } catch (_: SocketException) {
            // Expected when the test closes the server socket.
          }
        }
        .apply { start() }

    private fun handle(socket: Socket) {
      socket.use {
        readRequestHeaders(it)
        Thread.sleep(delayMillis)
        val body = styleJson.toByteArray(Charsets.UTF_8)
        val headers =
          "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        it.getOutputStream().write(headers.toByteArray(Charsets.UTF_8))
        it.getOutputStream().write(body)
      }
    }

    private fun readRequestHeaders(socket: Socket) {
      val input = socket.getInputStream()
      var matched = 0
      while (matched < HEADER_END.size) {
        val current = input.read()
        if (current == -1) break
        matched = if (current == HEADER_END[matched].toInt()) matched + 1 else 0
      }
    }

    override fun close() {
      server.close()
      thread.join(1_000)
    }

    private companion object {
      private val HEADER_END =
        byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
    }
  }

  private fun emptyStyleJson(name: String): String =
    """
      {
        "version": 8,
        "name": "$name",
        "sources": {},
        "layers": []
      }
    """
      .trimIndent()
}
