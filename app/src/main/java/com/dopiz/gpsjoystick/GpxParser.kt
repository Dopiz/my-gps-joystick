package com.dopiz.gpsjoystick

import android.util.Xml
import org.osmdroid.util.GeoPoint
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Minimal GPX reader built on the platform [XmlPullParser] (no third-party GPX lib).
 *
 * GPX tools vary in whether coordinates live under a track (`<trkpt>`), a route (`<rtept>`)
 * or a standalone waypoint (`<wpt>`); all three carry the coordinate in `lat`/`lon`
 * attributes. We collect every such point in document order, which is the drawing order for
 * a track/route preview.
 */
object GpxParser {

    /**
     * @return coordinate points in document order (possibly empty when the file has none).
     * @throws org.xmlpull.v1.XmlPullParserException / java.io.IOException on a malformed file,
     *   which the caller surfaces as a clear error rather than swallowing.
     */
    fun parse(input: InputStream): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name in POINT_TAGS) {
                val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                if (lat != null && lon != null) {
                    points.add(GeoPoint(lat, lon))
                }
            }
            event = parser.next()
        }
        return points
    }

    private val POINT_TAGS = setOf("trkpt", "rtept", "wpt")
}
