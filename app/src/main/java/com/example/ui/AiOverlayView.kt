package com.example.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.example.camera.AIPipeline

class AiOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var detections: List<AIPipeline.Detection> = emptyList()
    private var isAiEnabled: Boolean = false

    private val boxPaint = Paint().apply {
        color = Color.parseColor("#00E5FF") // glow cyan
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        textSize = 32f
        isAntiAlias = true
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private val tagBackgroundPaint = Paint().apply {
        color = Color.parseColor("#CC0A0C10") // dark glassmorphic translucent
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun updateDetections(newList: List<AIPipeline.Detection>, enabled: Boolean) {
        this.detections = newList
        this.isAiEnabled = enabled
        invalidate() // re-draw on canvas
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (!isAiEnabled || detections.isEmpty()) {
            return
        }

        for (det in detections) {
            // Coordinate transformation from percentage space (0-1) to pixel space
            val left = det.boundingBox.left * width
            val top = det.boundingBox.top * height
            val right = det.boundingBox.right * width
            val bottom = det.boundingBox.bottom * height

            // Draw cyan boundary box
            canvas.drawRect(left, top, right, bottom, boxPaint)

            // Labeled text construction: Wajah (Face) - Portrait (97%)
            val textString = "${det.label}·${(det.confidence * 100).toInt()}%"
            val textWidth = textPaint.measureText(textString)
            val padding = 12f
            val textHeight = 40f

            // Draw tag indicator background plate
            canvas.drawRect(
                left,
                top - textHeight - (padding * 2),
                left + textWidth + (padding * 2),
                top,
                tagBackgroundPaint
            )

            // Draw overlay text label
            canvas.drawText(
                textString,
                left + padding,
                top - padding - 4f,
                textPaint
            )
        }
    }
}
