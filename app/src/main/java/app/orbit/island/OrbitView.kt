package app.orbit.island

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.app.Notification
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.PowerManager
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * Kameranın (punch-hole) etrafındaki ada.
 *
 * - IDLE : görünmez dokunma alanı + belirli aralıklarla kamera çevresinde dönen halka (orbit)
 * - NOTIF: bildirim genişlemesi (spring animasyonu)
 * - MENU : kameraya dokununca küçük menü
 *
 * NOT: Bu sınıf SystemUI sürecinde çalışır. Modülün kaynaklarına (R) dokunma, her şey kodla çizilir.
 */
class OrbitView(ctx: Context, private val host: Host) : FrameLayout(ctx) {

    interface Host {
        fun setExpandedWindow(expanded: Boolean)
        fun openNotificationPanel()
        fun openSettings()
        fun launch(sbn: StatusBarNotification)
        fun isLocked(): Boolean
    }

    enum class Mode { IDLE, NOTIF, MENU }
    private enum class Fade { IN, OUT, NONE }

    class Geo(val cut: Rect, val screenW: Int, val screenH: Int)

    var geo: Geo = Geo(Rect(0, 0, 0, 0), 1080, 2400)
    var windowExpanded = false
    var mode = Mode.IDLE
        private set

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()
    private fun dpf(v: Float): Float = v * density

    private val power: PowerManager? = ctx.getSystemService(PowerManager::class.java)

    // ---- pill ----
    private val pillBg = GradientDrawable().apply {
        setColor(Color.BLACK)
        cornerRadius = 0f
    }
    private val pill = FrameLayout(ctx).apply {
        background = pillBg
        visibility = View.GONE
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
    }

    // ---- bildirim satırı ----
    private val iconBg = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0xFF3A3A3C.toInt())
    }
    private val iconView = ImageView(ctx).apply {
        background = iconBg
        setPadding(dp(7), dp(7), dp(7), dp(7))
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    private val titleView = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val textView = TextView(ctx).apply {
        setTextColor(0xFFB8B8BD.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val textCol = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val notifRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        visibility = View.GONE
    }

    // ---- menü satırı ----
    private fun menuButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dpf(20f)
            setColor(0x33FFFFFF)
        }
        setOnClickListener { onClick() }
    }

    private val menuRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        visibility = View.GONE
    }

    // ---- animasyon / durum ----
    private var anim: ValueAnimator? = null
    private var curW = 0
    private var curH = 0
    private var current: StatusBarNotification? = null
    private val spring = TimeInterpolator { input ->
        (1.0 - Math.exp(-7.0 * input) * Math.cos(11.0 * input)).toFloat()
    }
    private val collapseRunnable = Runnable { collapse() }

    // ---- orbit halkası ----
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private var spinning = false
    private var spinAngle = 0f
    private var spinAlpha = 0
    private var spinAnim: ValueAnimator? = null
    private var ringCx = 0f
    private var ringCy = 0f
    private var ringR = 0f
    private val spinRunnable = Runnable {
        val cfg = OrbitState.config
        if (cfg.orbitAnim && mode == Mode.IDLE && isAttachedToWindow && (power?.isInteractive != false)) {
            startSpin()
        }
        scheduleSpin()
    }

    // ---- dokunma ----
    private var downY = 0f
    private var swiped = false

    init {
        setWillNotDraw(false)
        isClickable = true
        addView(pill, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        textCol.addView(titleView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        textCol.addView(textView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        notifRow.addView(iconView, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(10) })
        notifRow.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        notifRow.setOnClickListener {
            current?.let { host.launch(it) }
            collapse()
        }

        val half = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            leftMargin = dp(4)
            rightMargin = dp(4)
        }
        menuRow.addView(menuButton("Notifications") {
            collapse()
            host.openNotificationPanel()
        }, half)
        menuRow.addView(menuButton("Settings") {
            collapse()
            host.openSettings()
        }, LinearLayout.LayoutParams(half))

        pill.addView(notifRow, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(48), Gravity.BOTTOM).apply {
            bottomMargin = dp(10)
            leftMargin = dp(14)
            rightMargin = dp(14)
        })
        pill.addView(menuRow, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(40), Gravity.BOTTOM).apply {
            bottomMargin = dp(10)
            leftMargin = dp(10)
            rightMargin = dp(10)
        })

        setOnClickListener { onRootClick() }
    }

    // =========================================================
    //  Geometri
    // =========================================================

    private fun pillTop(): Int = max(0, geo.cut.top - dp(2))

    fun compactSize(): Pair<Int, Int> {
        val c = geo.cut
        return Pair(max(c.width() + dp(4), dp(20)), max(c.height() + dp(4), dp(20)))
    }

    fun pillSize(m: Mode): Pair<Int, Int> {
        val maxW = min(geo.screenW - dp(24), dp(380))
        val base = (geo.cut.bottom - pillTop()) + dp(6) // kamera altı boşluğu
        return when (m) {
            Mode.NOTIF -> Pair(maxW, base + dp(48) + dp(10))
            Mode.MENU -> Pair(min(maxW, dp(260)), base + dp(40) + dp(10))
            Mode.IDLE -> compactSize()
        }
    }

    /** Pencerenin ekrandaki dikdörtgeni. Boştayken kamera çevresi, genişken tüm ada alanı. */
    fun windowRect(expanded: Boolean): Rect {
        val c = geo.cut
        if (!expanded) {
            val p = dp(8)
            return Rect(c.left - p, max(0, c.top - p), c.right + p, c.bottom + p)
        }
        val (nw, nh) = pillSize(Mode.NOTIF)
        val (mw, mh) = pillSize(Mode.MENU)
        val w = max(nw, mw)
        val h = max(nh, mh)
        val left = (c.centerX() - w / 2).coerceIn(0, max(0, geo.screenW - w))
        val top = pillTop()
        return Rect(left, top, left + w, top + h)
    }

    // =========================================================
    //  Genel API
    // =========================================================

    fun showNotification(sbn: StatusBarNotification) {
        bind(sbn)
        removeCallbacks(collapseRunnable)
        val (w, h) = pillSize(Mode.NOTIF)
        if (mode == Mode.IDLE) {
            openFromIdle(Mode.NOTIF)
        } else {
            setMode(Mode.NOTIF)
            animatePill(w, h, Fade.IN)
        }
        postDelayed(collapseRunnable, max(2, OrbitState.config.popupSec) * 1000L)
    }

    fun showMenu() {
        if (host.isLocked()) return
        removeCallbacks(collapseRunnable)
        when (mode) {
            Mode.IDLE -> openFromIdle(Mode.MENU)
            Mode.NOTIF -> {
                val (w, h) = pillSize(Mode.MENU)
                setMode(Mode.MENU)
                animatePill(w, h, Fade.IN)
            }
            Mode.MENU -> {
                collapse()
                return
            }
        }
        postDelayed(collapseRunnable, 6000L)
    }

    fun collapse() {
        removeCallbacks(collapseRunnable)
        if (mode == Mode.IDLE) return
        val (cw, ch) = compactSize()
        animatePill(cw, ch, Fade.OUT) {
            pill.visibility = View.GONE
            mode = Mode.IDLE
            host.setExpandedWindow(false)
            invalidate()
        }
    }

    fun resetToIdle() {
        anim?.cancel()
        spinAnim?.cancel()
        spinning = false
        removeCallbacks(collapseRunnable)
        pill.visibility = View.GONE
        mode = Mode.IDLE
        windowExpanded = false
    }

    fun rescheduleSpin() = scheduleSpin()

    // =========================================================
    //  İç işleyiş
    // =========================================================

    private fun onRootClick() {
        when (mode) {
            Mode.IDLE -> showMenu()
            Mode.MENU -> collapse()
            Mode.NOTIF -> {}
        }
    }

    private fun setMode(m: Mode) {
        mode = m
        showRow(m)
    }

    private fun showRow(m: Mode) {
        notifRow.visibility = if (m == Mode.NOTIF) View.VISIBLE else View.GONE
        menuRow.visibility = if (m == Mode.MENU) View.VISIBLE else View.GONE
    }

    private fun setContentAlpha(a: Float) {
        notifRow.alpha = a
        menuRow.alpha = a
    }

    private fun setPillSize(w: Int, h: Int) {
        curW = w
        curH = h
        val lp = pill.layoutParams
        lp.width = max(w, 1)
        lp.height = max(h, 1)
        pill.layoutParams = lp
        pillBg.cornerRadius = min(h / 2f, dpf(32f))
    }

    private fun openFromIdle(target: Mode) {
        anim?.cancel()
        spinAnim?.cancel()
        spinning = false
        mode = target
        host.setExpandedWindow(true)
        val (cw, ch) = compactSize()
        setPillSize(cw, ch)
        pill.visibility = View.VISIBLE
        showRow(target)
        setContentAlpha(0f)
        val (w, h) = pillSize(target)
        // pencere yeniden boyutlansın diye bir kare bekle
        postOnAnimation { animatePill(w, h, Fade.IN) }
    }

    private fun animatePill(toW: Int, toH: Int, fade: Fade, onEnd: (() -> Unit)? = null) {
        anim?.cancel()
        val fromW = curW
        val fromH = curH
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = if (fade == Fade.OUT) 300L else 460L
        a.interpolator = if (fade == Fade.OUT) AccelerateDecelerateInterpolator() else spring
        a.addUpdateListener { va ->
            val f = va.animatedValue as Float
            setPillSize(
                (fromW + (toW - fromW) * f).toInt(),
                (fromH + (toH - fromH) * f).toInt()
            )
            val t = (va.currentPlayTime.toFloat() / va.duration.toFloat()).coerceIn(0f, 1f)
            when (fade) {
                Fade.IN -> setContentAlpha(((t - 0.25f) / 0.5f).coerceIn(0f, 1f))
                Fade.OUT -> setContentAlpha((1f - t / 0.5f).coerceIn(0f, 1f))
                Fade.NONE -> {}
            }
        }
        a.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (!cancelled) onEnd?.invoke()
            }
        })
        anim = a
        a.start()
    }

    private fun bind(sbn: StatusBarNotification) {
        current = sbn
        try {
            val n = sbn.notification
            val ex = n.extras
            var title: CharSequence? = ex?.getCharSequence(Notification.EXTRA_TITLE)
            val text: CharSequence? = ex?.getCharSequence(Notification.EXTRA_TEXT)
                ?: ex?.getCharSequence(Notification.EXTRA_BIG_TEXT)
            if (title.isNullOrEmpty()) title = appLabel(sbn.packageName)
            titleView.text = title
            textView.text = text ?: ""
            textView.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE

            val d = try {
                n.smallIcon?.loadDrawable(context)?.mutate()
            } catch (t: Throwable) {
                null
            }
            d?.setTint(Color.WHITE)
            iconView.setImageDrawable(d)
            iconBg.setColor(if (n.color != 0) n.color else 0xFF3A3A3C.toInt())
        } catch (t: Throwable) {
            OLog.e("bind error", t)
        }
    }

    private fun appLabel(pkg: String): CharSequence = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))
    } catch (t: Throwable) {
        pkg
    }

    // ---- orbit ----

    private fun scheduleSpin() {
        removeCallbacks(spinRunnable)
        postDelayed(spinRunnable, max(3, OrbitState.config.orbitIntervalSec) * 1000L)
    }

    private fun startSpin() {
        if (spinning) return
        val c = geo.cut
        val wr = windowRect(false)
        ringCx = c.exactCenterX() - wr.left
        ringCy = c.exactCenterY() - wr.top
        ringR = max(c.width(), c.height()) / 2f + dpf(3.5f)
        ringPaint.strokeWidth = dpf(2.2f)
        val accent = OrbitState.config.accent
        ringPaint.shader = SweepGradient(
            ringCx, ringCy,
            intArrayOf(accent and 0x00FFFFFF, accent),
            floatArrayOf(0f, 1f)
        )
        spinning = true
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 1300L
        a.interpolator = DecelerateInterpolator(1.4f)
        a.addUpdateListener { va ->
            spinAngle = (va.animatedValue as Float) * 360f
            val t = (va.currentPlayTime.toFloat() / va.duration.toFloat()).coerceIn(0f, 1f)
            val k = min(1f, min(t / 0.15f, (1f - t) / 0.2f)).coerceIn(0f, 1f)
            spinAlpha = (k * 255f).toInt()
            invalidate()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                spinning = false
                invalidate()
            }
        })
        spinAnim = a
        a.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!spinning || mode != Mode.IDLE) return
        canvas.save()
        canvas.rotate(spinAngle, ringCx, ringCy)
        ringPaint.alpha = spinAlpha
        canvas.drawCircle(ringCx, ringCy, ringR, ringPaint)
        canvas.restore()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleSpin()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(spinRunnable)
        removeCallbacks(collapseRunnable)
        anim?.cancel()
        spinAnim?.cancel()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                if (mode == Mode.MENU) collapse()
                return true
            }
            MotionEvent.ACTION_DOWN -> {
                downY = ev.rawY
                swiped = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode != Mode.IDLE && !swiped && downY - ev.rawY > dp(28)) {
                    swiped = true
                    val cancel = MotionEvent.obtain(ev)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    collapse()
                    return true
                }
            }
        }
        if (swiped && ev.actionMasked != MotionEvent.ACTION_DOWN) return true
        return super.dispatchTouchEvent(ev)
    }
}
