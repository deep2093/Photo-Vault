package com.deepak.photovault.ui.shell

import android.content.Context
import android.graphics.PointF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.offline.OfflineManager

/** OpenFreeMap's styles (free, no key): Liberty in light themes, Dark in dark ones. */
private const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"

/** Map squares already seen are kept on the phone, up to about 100 MB. */
private const val CACHE_BYTES = 100L * 1024 * 1024

@Volatile private var mapLibreReady = false

private fun ensureMapLibre(context: Context) {
    if (mapLibreReady) return
    MapLibre.getInstance(context.applicationContext)
    runCatching {
        OfflineManager.getInstance(context.applicationContext).setMaximumAmbientCacheSize(
            CACHE_BYTES,
            object : OfflineManager.FileSourceCallback {
                override fun onSuccess() = Unit
                override fun onError(message: String) = Unit
            },
        )
    }
    mapLibreReady = true
}

@Composable
actual fun PhotoMap(
    modifier: Modifier,
    dark: Boolean,
    start: MapStart?,
    interactive: Boolean,
    controller: MapController?,
    onViewport: (MapViewport) -> Unit,
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    val report by rememberUpdatedState(onViewport)
    val view = remember {
        ensureMapLibre(context)
        MapView(context).apply { onCreate(null) }
    }
    // The map follows the screen's life: started, paused and closed with it.
    DisposableEffect(lifecycle, view) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            runCatching { view.onPause() }
            runCatching { view.onStop() }
            runCatching { view.onDestroy() }
        }
    }
    DisposableEffect(view, dark) {
        var map: MapLibreMap? = null
        view.getMapAsync { m ->
            map = m
            m.setStyle(if (dark) DARK_STYLE else LIGHT_STYLE)
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isCompassEnabled = interactive
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            if (!interactive) m.uiSettings.setAllGesturesEnabled(false)
            if (start != null && m.cameraPosition.zoom < 0.5) {
                m.cameraPosition = CameraPosition.Builder().target(LatLng(start.lat, start.lon)).zoom(start.zoom).build()
            }
            fun send() {
                val region = m.projection.visibleRegion.latLngBounds
                val w = view.width.toFloat()
                val h = view.height.toFloat()
                report(
                    MapViewport(
                        zoom = m.cameraPosition.zoom,
                        north = region.latitudeNorth,
                        south = region.latitudeSouth,
                        west = region.longitudeWest,
                        east = region.longitudeEast,
                        widthPx = w,
                        heightPx = h,
                        project = { lat, lon ->
                            val p: PointF = m.projection.toScreenLocation(LatLng(lat, lon))
                            if (p.x < -200 || p.y < -200 || p.x > w + 200 || p.y > h + 200) null else Offset(p.x, p.y)
                        },
                    ),
                )
            }
            m.addOnCameraMoveListener { send() }
            m.addOnCameraIdleListener { send() }
            view.addOnDidFinishLoadingStyleListener { send() }
            view.post { send() }
            controller?.flyTo = { lat, lon, zoom ->
                m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom), 600)
            }
        }
        onDispose {
            controller?.flyTo = null
            map = null
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}
