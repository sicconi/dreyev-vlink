package com.dreyev.vlink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreyev.vlink.BuildConfig
import com.dreyev.vlink.obd.UnitPrefs
import com.dreyev.vlink.obd.VehicleState
import com.dreyev.vlink.sensors.PhoneLocation
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState

/** True when a real Google map can be shown (a key was built in, and not in a screenshot test). */
@Composable
fun mapsAvailable(): Boolean = BuildConfig.HAS_MAPS_KEY && !LocalInspectionMode.current

/**
 * Google map with the vehicle's trace. [follow] keeps the car centered (and heading-up when
 * moving); otherwise the user can pan and zoom freely. Falls back to the offline trail drawing
 * when no Maps key is built in.
 */
@Composable
fun TraceMap(
    trail: List<Pair<Double, Double>>,
    headingDeg: Double?,
    modifier: Modifier = Modifier,
    follow: Boolean = true,
    zoom: Float = 16f,
    interactive: Boolean = true,
    noticeTop: Dp = 8.dp,
) {
    if (!mapsAvailable()) {
        Box(modifier.background(C.cardInner)) {
            TrailView(trail, Modifier.fillMaxSize())
            if (!BuildConfig.HAS_MAPS_KEY && !LocalInspectionMode.current) {
                Text("Map needs a Google Maps key (see README)", Modifier.align(Alignment.TopCenter).padding(top = noticeTop, start = 8.dp, end = 8.dp), color = C.textFaint, fontSize = 10.sp)
            }
        }
        return
    }
    val last = trail.lastOrNull()?.let { LatLng(it.first, it.second) }
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(last ?: LatLng(0.0, 0.0), if (last == null) 2f else zoom) }
    val dark = C.isDark
    val style = remember(dark) { if (dark) MapStyleOptions(DARK_MAP_STYLE) else null }
    GoogleMap(
        modifier = modifier,
        cameraPositionState = camera,
        properties = MapProperties(mapStyleOptions = style, isBuildingEnabled = false, isTrafficEnabled = false),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false, mapToolbarEnabled = false, myLocationButtonEnabled = false,
            scrollGesturesEnabled = interactive, zoomGesturesEnabled = interactive, rotationGesturesEnabled = interactive, tiltGesturesEnabled = false,
        ),
    ) {
        if (trail.size >= 2) {
            Polyline(points = trail.map { LatLng(it.first, it.second) }, color = C.obd, width = 10f)
        }
        if (last != null) {
            Circle(center = last, radius = 6.0, fillColor = C.obd, strokeColor = C.card, strokeWidth = 4f)
        }
    }
    LaunchedEffect(last, follow, headingDeg) {
        if (follow && last != null) {
            val pos = CameraPosition.Builder().target(last).zoom(if (camera.position.zoom < 5f) zoom else camera.position.zoom)
                .bearing((headingDeg ?: 0.0).toFloat()).build()
            camera.animate(CameraUpdateFactory.newCameraPosition(pos), 600)
        }
    }
}

/** Fourth tab: full-screen map with the trace, a follow toggle, distance and speed. */
@Composable
fun MapScreen(s: VehicleState, u: UnitPrefs, trail: List<Pair<Double, Double>>, onClear: () -> Unit) {
    var follow by rememberSaveable { mutableStateOf(true) }
    val moving = (s.gps?.value?.speedKmh ?: 0.0) > 5.0
    Box(Modifier.fillMaxSize()) {
        TraceMap(trail, if (moving) s.gps?.value?.headingDeg else null, Modifier.fillMaxSize(), follow = follow, zoom = 16.5f, noticeTop = 84.dp)
        // Top overlay: speed and trace length
        Row(Modifier.align(Alignment.TopStart).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MapChip("Speed", (s.speedKmh?.value ?: s.gps?.value?.speedKmh)?.let { u.fmtSpeed(it) + " " + u.speedUnit } ?: "N/A")
            MapChip("Trace", u.fmtDistance(PhoneLocation.lengthM(trail) / 1000.0) + " " + u.distanceUnit)
            if (s.isEv) MapChip("Power", s.hvPowerKw?.value?.let { String.format(java.util.Locale.US, "%.0f kW", it) } ?: "N/A", powerColor(s.hvPowerKw?.value))
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
            MapButton(if (follow) "Following" else "Follow car", if (follow) C.obd else C.textDim) { follow = !follow }
            MapButton("Clear trace", C.textDim, onClear)
        }
        if (s.gps == null) {
            Text("Waiting for GPS fix…", Modifier.align(Alignment.BottomStart).padding(16.dp).clip(RoundedCornerShape(8.dp)).background(C.card.copy(alpha = 0.9f)).padding(8.dp), color = C.textDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MapChip(label: String, value: String, color: androidx.compose.ui.graphics.Color = C.text) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(C.card.copy(alpha = 0.92f)).border(1.dp, C.border, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(label.uppercase(), color = C.textFaint, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = NumberFont, maxLines = 1)
    }
}

@Composable
private fun MapButton(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(C.card.copy(alpha = 0.95f)).border(1.dp, color, RoundedCornerShape(22.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

/** Night map style matching the dashboard (Google's "night" palette, simplified). */
const val DARK_MAP_STYLE = """[
  {"elementType":"geometry","stylers":[{"color":"#0e1217"}]},
  {"elementType":"labels.text.fill","stylers":[{"color":"#8c99a6"}]},
  {"elementType":"labels.text.stroke","stylers":[{"color":"#0b0e12"}]},
  {"featureType":"poi","stylers":[{"visibility":"off"}]},
  {"featureType":"transit","stylers":[{"visibility":"off"}]},
  {"featureType":"road","elementType":"geometry","stylers":[{"color":"#232c37"}]},
  {"featureType":"road","elementType":"geometry.stroke","stylers":[{"color":"#141a21"}]},
  {"featureType":"road.highway","elementType":"geometry","stylers":[{"color":"#2f3b48"}]},
  {"featureType":"road","elementType":"labels.icon","stylers":[{"visibility":"off"}]},
  {"featureType":"water","elementType":"geometry","stylers":[{"color":"#0a1a24"}]},
  {"featureType":"landscape.natural","elementType":"geometry","stylers":[{"color":"#10151b"}]},
  {"featureType":"administrative","elementType":"geometry","stylers":[{"color":"#2a3440"}]}
]"""
