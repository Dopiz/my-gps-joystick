package com.dopiz.gpsjoystick

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.view.MotionEvent
import android.view.View

/**
 * 連點選點層的畫布：全螢幕吃觸控，使用者每點一下放一個帶編號（1/2/3）的綠色圓形標記，最多 3 個。
 * 座標以螢幕絕對座標（rawX/rawY）記錄，供 [AutoTapService] dispatchGesture 使用；視窗不一定從
 * 螢幕 (0,0) 開始（例如未蓋到狀態列），畫標記時要扣掉視窗在螢幕上的偏移，標記才會落在手指點的位置。
 */
class TapPickView(context: Context) : View(context) {

    val points = ArrayList<PointF>()
    var onPointsChanged: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF22C55E.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f * density
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        textSize = 16f * density
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && points.size < 3) {
            points.add(PointF(event.rawX, event.rawY))
            onPointsChanged()
            invalidate()
        }
        return true
    }

    fun clearPoints() {
        points.clear()
        onPointsChanged()
        invalidate()
    }

    private val screenLoc = IntArray(2)

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        // points 存的是螢幕絕對座標；扣掉本 view 在螢幕上的位置換回 view 座標再畫。
        getLocationOnScreen(screenLoc)
        val r = 14f * density
        val fm = label.fontMetrics
        points.forEachIndexed { i, p ->
            val x = p.x - screenLoc[0]
            val y = p.y - screenLoc[1]
            canvas.drawCircle(x, y, r, disc)
            canvas.drawCircle(x, y, r, ring)
            canvas.drawText("${i + 1}", x, y - (fm.ascent + fm.descent) / 2f, label)
        }
    }
}
