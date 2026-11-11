package app.orbit.island

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.app.Notification
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.PowerManager
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
 * Kameranın (punch-hole) çevresindeki ada. Kamera yoksa status bar'ın sağında elips şeklinde "sanal ada".
 *
 * - IDLE : görünmez dokunma alanı. Pil yüzdesi açıksa kamera yanlarında (ya da elipsin içinde) yazar.
 *          Belirli aralıklarla dönen orbit halkası.
 * - NOTIF: bildirim genişlemesi (spring animasyonu)
 * - MENU : küçük menü
 *
 * Dokunma: ayara göre "gizlenen ikonları göster" veya "menü"; uzun basış diğeri.
 * Genişleyen pill kameraya ortalanır; kamera kenara yakınsa ekran içinde kalacak şekilde kayar.
 *
 * NOT: Bu sınıf SystemUI sürecinde çalışır. Modülün kaynaklarına (R) dokunma, her şey kodla çizilir.
 */
class OrbitView(ctx: Context, private val host: Host) : FrameLayout(ctx) {

    interface Host {
        fun setExpandedWindow(expanded: Boolean)
        fun openNotificationPanel()
        fun openSettings()
        fun revealStatus()
        fun launch(sbn: StatusBarNotification)
        fun isLocked(): Boolean
    }

    enum class Mode { IDLE, NOTIF, MENU }
    private enum class Fade { IN, OUT, NONE }

    /** cut: kamera deliği (ekran piksel koordinatı). virtual: gerçek kamera yok, sanal elips. */
    class Geo(val cut: Rect, val screenW: Int, val screenH: Int, val virtual: Boolean)

    var geo: Geo = Geo(Rect(0, 0, 0, 0), 1080, 2400, false)
        private set
    var windowExpanded = false
    var mode = Mode.IDLE
        private set

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()
    private fun dpf(v: Float): Float = v * density

    private val power: PowerManager? = ctx.getSystemService(PowerManager::class.java)

    // ---- pil ----
    private var batteryLevel = -1
    private var charging = false
    val hasBattery: Boolean get() = batteryLevel >= 0

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

    // ---- boşta bilgi (pil) ----
    private fun infoText() = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        maxLines = 1
        visibility = View.GONE
    }

    private val infoLeft = infoText()
    private val infoRight = infoText()

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
    private var curX = 0f
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
    private val ringRect = RectF()
    private val ringMatrix = Matrix()
    private var ringShader: SweepGradient? = null
    private var ringRadius = 0f
    private var ringCx = 0f
    private var ringCy = 0f
    private var spinning = false
    private var spinAngle = 0f
    private var spinAlpha = 0
    private var spinAnim: ValueAnimator? = null
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
        isLongClickable = true
        addView(pill, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.TOP or Gravity.START))

        pill.addView(infoLeft, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.TOP or Gravity.START))
        pill.addView(infoRight, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.TOP or Gravity.END))

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
        setOnLongClickListener { onRootLongClick() }
    }

    // =========================================================
    //  Geometri
    // =========================================================

    private fun edgeMargin(): Int = dp(12)

    private fun speed(): Float = OrbitState.config.animSpeedPct.coerceIn(50, 200) / 100f

    private fun dur(ms: Long): Long = (ms / speed()).toLong()

    /** Gerçek kamerada pill deliği 2dp taşarak sarar; sanal elipste tam elips boyutundadır. */
    private fun inflate(): Int = if (geo.virtual) 0 else dp(2)

    private fun pillTop(): Int = max(0, geo.cut.top - inflate())

    private fun infoEnabled(): Boolean = OrbitState.config.idleBattery && batteryLevel >= 0

    /** Boştaki pil yazısı için kameranın solunda/sağında ayrılan genişlikler (sanal elipste yazı içeride). */
    private fun wings(): Pair<Int, Int> {
        if (!infoEnabled() || geo.virtual) return Pair(0, 0)
        val w = dp(46)
        val c = geo.cut
        val roomL = c.left - edgeMargin()
        val roomR = geo.screenW - c.right - edgeMargin()
        val centered = Math.abs(c.centerX() - geo.screenW / 2) < geo.screenW * 0.15f
        return when {
            centered && roomL >= w && roomR >= w -> Pair(w, w)
            roomR >= roomL && roomR >= w -> Pair(0, w)
            roomL >= w -> Pair(w, 0)
            else -> Pair(0, 0)
        }
    }

    fun compactSize(): Pair<Int, Int> {
        val c = geo.cut
        val i = inflate()
        val (wl, wr) = wings()
        return Pair(max(c.width() + 2 * i + wl + wr, dp(20)), max(c.height() + 2 * i, dp(16)))
    }

    private fun compactLeft(): Int = geo.cut.left - inflate() - wings().first

    fun pillSize(m: Mode): Pair<Int, Int> {
        val maxW = min(geo.screenW - 2 * edgeMargin(), dp(OrbitState.config.notifWidthDp))
        // içeriğin başladığı yer: gerçek kamerada deliğin altı, sanal elipste üstten küçük boşluk
        val base = if (geo.virtual) dp(10) else (geo.cut.bottom - pillTop()) + dp(6)
        return when (m) {
            Mode.NOTIF -> Pair(maxW, base + dp(48) + dp(10))
            Mode.MENU -> Pair(min(maxW, dp(260)), base + dp(40) + dp(10))
            Mode.IDLE -> compactSize()
        }
    }

    /** Pill'in ekran koordinatındaki sol kenarı: kameraya ortalı, ekran dışına taşarsa içeri kaydırılmış. */
    private fun pillLeft(m: Mode): Int {
        if (m == Mode.IDLE) return compactLeft()
        val w = pillSize(m).first
        val lo = edgeMargin()
        val hi = max(lo, geo.screenW - w - edgeMargin())
        return (geo.cut.centerX() - w / 2).coerceIn(lo, hi)
    }

    /** Pencerenin ekrandaki dikdörtgeni. Boştayken küçük pill çevresi, genişken tüm ada alanı. */
    fun windowRect(expanded: Boolean): Rect {
        val (cw, ch) = compactSize()
        val cl = compactLeft()
        if (!expanded) {
            val p = dp(8)
            val top = pillTop()
            return Rect(cl - p, max(0, top - p), cl + cw + p, top + ch + p)
        }
        val (nw, nh) = pillSize(Mode.NOTIF)
        val (mw, mh) = pillSize(Mode.MENU)
        val nl = pillLeft(Mode.NOTIF)
        val ml = pillLeft(Mode.MENU)
        val left = min(min(nl, ml), cl)
        val right = max(max(nl + nw, ml + mw), cl + cw)
        val top = pillTop()
        return Rect(left, top, right, top + max(nh, mh))
    }

    // =========================================================
    //  Genel API
    // =========================================================

    fun applyGeo(g: Geo) {
        geo = g
        resetToIdle()
    }

    fun setBattery(level: Int, isCharging: Boolean) {
        batteryLevel = level
        charging = isCharging
        if (mode == Mode.IDLE) applyIdleLook() else applyInfoLayout()
    }

    fun showNotification(sbn: StatusBarNotification) {
        bind(sbn)
        removeCallbacks(collapseRunnable)
        haptic(HapticFeedbackConstants.CONTEXT_CLICK)
        if (mode == Mode.IDLE) {
            openFromIdle(Mode.NOTIF)
        } else {
            val (w, h) = pillSize(Mode.NOTIF)
            setMode(Mode.NOTIF)
            animatePill(w, h, (pillLeft(Mode.NOTIF) - windowRect(true).left).toFloat(), Fade.IN)
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
                animatePill(w, h, (pillLeft(Mode.MENU) - windowRect(true).left).toFloat(), Fade.IN)
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
        val toX = (compactLeft() - windowRect(true).left).toFloat()
        animatePill(cw, ch, toX, Fade.OUT) {
            mode = Mode.IDLE
            host.setExpandedWindow(false)
            applyIdleLook()
            invalidate()
        }
    }

    fun resetToIdle() {
        anim?.cancel()
        spinAnim?.cancel()
        spinning = false
        removeCallbacks(collapseRunnable)
        mode = Mode.IDLE
        windowExpanded = false
        applyIdleLook()
    }

    fun rescheduleSpin() = scheduleSpin()

    // =========================================================
    //  Dokunma davranışı
    // =========================================================

    private fun haptic(kind: Int) {
        if (OrbitState.config.haptics) performHapticFeedback(kind)
    }

    private fun doReveal() {
        haptic(HapticFeedbackConstants.CONTEXT_CLICK)
        host.revealStatus()
        startSpin() // dokunuşa görsel geri bildirim
    }

    private fun onRootClick() {
        when (mode) {
            Mode.IDLE -> if (OrbitState.config.tapAction == 1) showMenu() else doReveal()
            Mode.MENU -> collapse()
            Mode.NOTIF -> {}
        }
    }

    private fun onRootLongClick(): Boolean {
        if (mode != Mode.IDLE) return false
        haptic(HapticFeedbackConstants.LONG_PRESS)
        if (OrbitState.config.tapAction == 1) doReveal() else showMenu()
        return true
    }

    // =========================================================
    //  İç işleyiş
    // =========================================================

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

    private fun setInfoAlpha(a: Float) {
        infoLeft.alpha = a
        infoRight.alpha = a
    }

    /** Pil yazılarının yerleşimi ve içeriği. */
    private fun applyInfoLayout() {
        val en = infoEnabled()
        val (wl, wr) = wings()
        val (_, ch) = compactSize()
        val col = when {
            charging -> 0xFF34C759.toInt()
            batteryLevel in 0..15 -> 0xFFFF453A.toInt()
            else -> Color.WHITE
        }
        val label = "$batteryLevel%"
        if (geo.virtual) {
            infoLeft.visibility = View.GONE
            infoRight.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ch, Gravity.TOP or Gravity.START)
            infoRight.visibility = if (en) View.VISIBLE else View.GONE
            infoRight.text = label
            infoRight.setTextColor(col)
        } else {
            infoLeft.layoutParams = FrameLayout.LayoutParams(max(wl, 1), ch, Gravity.TOP or Gravity.START)
            infoRight.layoutParams = FrameLayout.LayoutParams(max(wr, 1), ch, Gravity.TOP or Gravity.END)
            infoLeft.visibility = if (wl > 0 && wr > 0 && charging) View.VISIBLE else View.GONE
            infoLeft.text = "\u26A1"
            infoLeft.setTextColor(col)
            // iki kanat da varsa yazı sağda, tek kanat varsa o kanatta
            infoRight.visibility = if (wr > 0) View.VISIBLE else View.GONE
            infoRight.text = label
            infoRight.setTextColor(col)
            if (wl > 0 && wr == 0) {
                infoLeft.visibility = View.VISIBLE
                infoLeft.text = label
            }
        }
    }

    /** Boşta: gerçek kamerada pill görünmez (pil yazısı varsa kanatlı), sanal modda siyah elips görünür. */
    private fun applyIdleLook() {
        showRow(Mode.IDLE)
        applyInfoLayout()
        setInfoAlpha(1f)
        val (wl, wr) = wings()
        val showPill = (geo.virtual && OrbitState.config.virtualIdleVisible) || (wl + wr) > 0
        if (showPill) {
            val (cw, ch) = compactSize()
            pill.visibility = View.VISIBLE
            setContentAlpha(0f)
            setPillGeo(cw, ch, (compactLeft() - windowRect(windowExpanded).left).toFloat())
        } else {
            pill.visibility = View.GONE
        }
    }

    private fun setPillGeo(w: Int, h: Int, x: Float) {
        curW = w
        curH = h
        curX = x
        val lp = pill.layoutParams
        lp.width = max(w, 1)
        lp.height = max(h, 1)
        pill.layoutParams = lp
        pill.translationX = x
        pill.translationY = (pillTop() - windowRect(windowExpanded).top).toFloat()
        pillBg.cornerRadius = min(h / 2f, dpf(32f))
    }

    private fun openFromIdle(target: Mode) {
        anim?.cancel()
        spinAnim?.cancel()
        spinning = false
        mode = target
        host.setExpandedWindow(true)
        val (cw, ch) = compactSize()
        val ex = windowRect(true)
        setPillGeo(cw, ch, (compactLeft() - ex.left).toFloat())
        pill.visibility = View.VISIBLE
        showRow(target)
        setContentAlpha(0f)
        val (w, h) = pillSize(target)
        val toX = (pillLeft(target) - ex.left).toFloat()
        // pencere yeniden boyutlansın diye bir kare bekle
        postOnAnimation { animatePill(w, h, toX, Fade.IN) }
    }

    private fun animatePill(toW: Int, toH: Int, toX: Float, fade: Fade, onEnd: (() -> Unit)? = null) {
        anim?.cancel()
        val fromW = curW
        val fromH = curH
        val fromX = curX
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = dur(if (fade == Fade.OUT) 300L else 460L)
        a.interpolator = if (fade == Fade.OUT) AccelerateDecelerateInterpolator() else spring
        a.addUpdateListener { va ->
            val f = va.animatedValue as Float
            setPillGeo(
                (fromW + (toW - fromW) * f).toInt(),
                (fromH + (toH - fromH) * f).toInt(),
                fromX + (toX - fromX) * f
            )
            val t = (va.currentPlayTime.toFloat() / va.duration.toFloat()).coerceIn(0f, 1f)
            when (fade) {
                Fade.IN -> {
                    setContentAlpha(((t - 0.25f) / 0.5f).coerceIn(0f, 1f))
                    setInfoAlpha((1f - t / 0.25f).coerceIn(0f, 1f))
                }
                Fade.OUT -> {
                    setContentAlpha((1f - t / 0.5f).coerceIn(0f, 1f))
                    setInfoAlpha(((t - 0.7f) / 0.3f).coerceIn(0f, 1f))
                }
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
        if (spinning || mode != Mode.IDLE) return
        val (cw, ch) = compactSize()
        val wr = windowRect(false)
        val gap = dpf(1.5f)
        val l = compactLeft() - wr.left
        val t = pillTop() - wr.top
        // Halka küçük pill'i (kamera deliği, kanatlar ya da sanal elips) sarar
        ringRect.set(l - gap, t - gap, l + cw + gap, t + ch + gap)
        ringRadius = min(ringRect.width(), ringRect.height()) / 2f
        ringCx = ringRect.centerX()
        ringCy = ringRect.centerY()
        ringPaint.strokeWidth = dpf(2.2f)
        val accent = OrbitState.config.accent
        val sh = SweepGradient(
            ringCx, ringCy,
            intArrayOf(accent and 0x00FFFFFF, accent),
            floatArrayOf(0f, 1f)
        )
        ringShader = sh
        ringPaint.shader = sh
        spinning = true
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = dur(1300L)
        a.interpolator = DecelerateInterpolator(1.4f)
        a.addUpdateListener { va ->
            spinAngle = (va.animatedValue as Float) * 360f
            ringMatrix.setRotate(spinAngle, ringCx, ringCy)
            ringShader?.setLocalMatrix(ringMatrix)
            val t2 = (va.currentPlayTime.toFloat() / va.duration.toFloat()).coerceIn(0f, 1f)
            val k = min(1f, min(t2 / 0.15f, (1f - t2) / 0.2f)).coerceIn(0f, 1f)
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
        ringPaint.alpha = spinAlpha
        canvas.drawRoundRect(ringRect, ringRadius, ringRadius, ringPaint)
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
