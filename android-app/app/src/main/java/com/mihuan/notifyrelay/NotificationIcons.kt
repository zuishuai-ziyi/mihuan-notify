package com.mihuan.notifyrelay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import androidx.core.graphics.drawable.IconCompat
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 通知图标自定义：常驻通知（前台服务通知）与转发通知（每条转发消息通知）各一个槽位。
 *
 * 存储策略：以「文件是否存在」为准（文件存在 = 已自定义），图片落在 ctx.filesDir 下
 * （notif_icon_service.png / notif_icon_forward.png）。大图不进 SharedPreferences——
 * 那是明文 XML，塞 base64 会撑爆存储并拖慢每次读写。
 * GetContent 返回的 Uri 不可长期依赖（无持久权限、也可能被系统回收），
 * 因此选中后必须立刻复制成本地 PNG。
 *
 * 渲染策略：用户所选图片「双用」——
 *   1) 原图（降采样后）作为 largeIcon；
 *   2) 自动生成白色 alpha 剪影作为 smallIcon（系统状态栏的小图标只取 alpha 形状，
 *      直接塞彩色原图只会变成一坨色块）。
 *
 * 尺寸上限（避免 OOM 与 Binder 传输超限）：
 *   - 落盘原图长边 ≤ 512px：够清晰，又不至于让内部存储和解码抖动。
 *   - largeIcon 长边 ≤ 256px：通知的 extras 走 Binder 传给系统进程，
 *     单张图过大会触发 TransactionTooLargeException（实测 256KB 量级比较安全）。
 *   - smallIcon ≤ 96x96：状态栏小图标实际只有 24~48dp，且每条通知都要传它，必须小。
 *
 * 线程：所有方法都是**同步阻塞**的（文件 IO + 位图解码 + 剪影计算）。
 * 调用方必须放到 IO 线程执行（UI 里用 rememberCoroutineScope + Dispatchers.IO），
 * 否则会卡住主线程。剪影计算刻意做得「宁可粗略」：任何异常都返回 null，
 * 由调用方回退默认图标，而不是把异常抛给用户。
 */
object NotificationIcons {

    /** 槽位：常驻通知 / 转发通知。 */
    enum class Slot { SERVICE, FORWARD }

    private const val TAG = "NotificationIcons"

    /** 落盘原图长边上限（px）。 */
    private const val MAX_STORED_DIM = 512

    /** largeIcon 长边上限（px）——通知 extras 走 Binder，不能太大。 */
    private const val MAX_LARGE_DIM = 256

    /** smallIcon 剪影边长上限（px）——状态栏小图标，必须小。 */
    private const val MAX_SMALL_DIM = 96

    private const val FILE_SERVICE = "notif_icon_service.png"
    private const val FILE_FORWARD = "notif_icon_forward.png"
    private const val TMP_SUFFIX = ".tmp"

    /**
     * 内存缓存：转发通知可能高频发送，每次都解码 PNG 太浪费。
     * 用「文件指纹（lastModified + 文件大小）」判定缓存是否过期；
     * 换图/清除时主动 remove 对应槽位。
     */
    private class CacheEntry(val stamp: Long, val large: Bitmap?, val small: IconCompat?)

    private val cache = ConcurrentHashMap<Slot, CacheEntry>()

    // ---------------- 文件 ----------------

    private fun fileName(slot: Slot): String = when (slot) {
        Slot.SERVICE -> FILE_SERVICE
        Slot.FORWARD -> FILE_FORWARD
    }

    /** 自定义图标的落盘文件（内部存储，随应用卸载删除）。 */
    fun iconFile(ctx: Context, slot: Slot): File = File(ctx.filesDir, fileName(slot))

    /** 该槽位是否已自定义（文件存在且非空）。 */
    fun hasCustom(ctx: Context, slot: Slot): Boolean =
        iconFile(ctx, slot).let { it.exists() && it.length() > 0L }

    // ---------------- 写入 ----------------

    /**
     * 从 Uri（相册/文件选择器）读取图片并落地为本地 PNG。成功返回 true。
     *
     * 流程：先探尺寸 → 降采样解码（长边 ≤512）→ 按 EXIF 方向纠正 → 缩放到 ≤512
     *      → 写临时文件 → 改名。
     * 「先写临时文件再改名」是为了避免中途失败留下半截 PNG（半截文件会被当成已自定义）。
     */
    fun setFromUri(ctx: Context, slot: Slot, uri: Uri): Boolean {
        var tmpFile: File? = null
        // 待落盘的源位图（已按 EXIF 纠正方向）与缩放副本：成功/异常两条出口都要回收
        var src: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            val resolver = ctx.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // 注意：inJustDecodeBounds = true 时 decodeStream() 返回 null（只填尺寸字段），
            // 不能拿它的返回值当「流已打开」的判据，必须单独判断 openInputStream 是否为 null。
            val boundsStream = resolver.openInputStream(uri) ?: return false
            boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_STORED_DIM) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return false

            // EXIF 方向纠正（解码后、缩放前）：相册竖拍照片只带 Orientation=6，
            // BitmapFactory 不会自动旋转，直接落盘会横躺 90°。
            val oriented: Bitmap = applyExifOrientation(ctx, uri, decoded)
            src = oriented
            if (oriented !== decoded) decoded.recycle() // 原图已不再使用，及时回收

            val longEdge = maxOf(oriented.width, oriented.height)
            val scale = if (longEdge > MAX_STORED_DIM) MAX_STORED_DIM.toFloat() / longEdge else 1f
            val bmp = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    oriented,
                    (oriented.width * scale + 0.5f).toInt().coerceAtLeast(1),
                    (oriented.height * scale + 0.5f).toInt().coerceAtLeast(1),
                    true
                ).also { scaled = it }
            } else oriented

            val target = iconFile(ctx, slot)
            val tmp = File(target.absolutePath + TMP_SUFFIX)
            tmpFile = tmp
            tmp.outputStream().use { out ->
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("compress failed")
            }
            // rename 在 Linux 上会覆盖同名文件；个别文件系统不允许时退回「删旧再改名」
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("rename failed")
            }
            cache.remove(slot)
            true
        } catch (e: Exception) {
            Log.w(TAG, "setFromUri failed: ${e.message}")
            tmpFile?.delete()
            false
        } finally {
            // 落盘后位图不再被任何人持有，成功与异常路径都要回收（避免泄漏）
            scaled?.let { if (it !== src) it.recycle() }
            src?.recycle()
        }
    }

    /**
     * 按 EXIF 方向纠正位图：相册竖拍照片只带 Orientation=6/8/3，BitmapFactory 解码时
     * 会忽略这些标记，导致落盘后横躺。这里用平台自带的 android.media.ExifInterface
     * （minSdk 26 可直接用，不引第三方依赖）读方向，再用 Matrix 做旋转/翻转。
     *
     * 读取或纠正失败一律忽略（保持原图），不能因此让选图失败。
     */
    private fun applyExifOrientation(ctx: Context, uri: Uri, src: Bitmap): Bitmap {
        return try {
            // buffered()：ExifInterface 读流时可能需要 mark/reset，包一层更稳
            val input = ctx.contentResolver.openInputStream(uri)?.buffered() ?: return src
            val orientation = input.use {
                val exif = ExifInterface(it)
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.setRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.setRotate(-90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
                else -> return src
            }
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        } catch (e: Exception) {
            Log.w(TAG, "exif orientation ignored: ${e.message}")
            src
        }
    }

    /** 恢复默认：删除该槽位的图片文件并清缓存。 */
    fun clear(ctx: Context, slot: Slot) {
        try {
            val f = iconFile(ctx, slot)
            f.delete()
            File(f.absolutePath + TMP_SUFFIX).delete()
        } catch (e: Exception) {
            Log.w(TAG, "clear failed: ${e.message}")
        }
        cache.remove(slot)
    }

    // ---------------- 读取 ----------------

    /** largeIcon：长边 ≤256px 的位图；未自定义或解码失败返回 null（调用方回退默认图标）。 */
    fun loadLargeIcon(ctx: Context, slot: Slot): Bitmap? = ensure(ctx, slot).large

    /** smallIcon：白色 alpha 剪影（≤96x96）；未自定义或生成失败返回 null。 */
    fun loadSmallIcon(ctx: Context, slot: Slot): IconCompat? = ensure(ctx, slot).small

    /** 取缓存；文件指纹变化时重新解码。 */
    private fun ensure(ctx: Context, slot: Slot): CacheEntry {
        val file = iconFile(ctx, slot)
        if (!file.exists() || file.length() <= 0L) {
            cache.remove(slot)
            return CacheEntry(0L, null, null)
        }
        // 指纹：文件长度左移 22 位后与 lastModified 异或
        // （比 lastModified*1000+length 更难碰撞：长度与时间戳的十进制位不重叠）
        val stamp = (file.length() shl 22) xor file.lastModified()
        cache[slot]?.let { if (it.stamp == stamp) return it }
        val entry = decode(file, stamp)
        cache[slot] = entry
        return entry
    }

    /** 解码落盘 PNG：一次解码同时产出 largeIcon（≤256）与剪影（≤96）。 */
    private fun decode(file: File, stamp: Long): CacheEntry {
        var large: Bitmap?
        var small: IconCompat?
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return CacheEntry(stamp, null, null)

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_LARGE_DIM) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, opts)
                ?: return CacheEntry(stamp, null, null)

            // inSampleSize 只能取 2 的幂次，实际边长仍可能超上限，再精确缩一次
            val edge = maxOf(decoded.width, decoded.height)
            large = if (edge > MAX_LARGE_DIM) {
                val s = MAX_LARGE_DIM.toFloat() / edge
                val scaled = Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * s + 0.5f).toInt().coerceAtLeast(1),
                    (decoded.height * s + 0.5f).toInt().coerceAtLeast(1),
                    true
                )
                if (scaled !== decoded) decoded.recycle()
                scaled
            } else decoded

            small = buildSilhouette(large)
        } catch (e: Exception) {
            Log.w(TAG, "decode failed: ${e.message}")
            large = null
            small = null
        }
        return CacheEntry(stamp, large, small)
    }

    /**
     * 生成白色 alpha 剪影（长边 ≤96px）：
     *  - 有透明像素：直接沿用原 alpha（这才是用户想要的形状），颜色统一置白；
     *  - 全不透明：按亮度做软阈值，并自动判断极性（浅底深图 / 深底浅图）。
     * 宁可粗略，也不要抛异常——失败返回 null，由调用方回退默认图标。
     */
    private fun buildSilhouette(src: Bitmap): IconCompat? = try {
        val scale = minOf(1f, MAX_SMALL_DIM.toFloat() / maxOf(src.width, src.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                src,
                (src.width * scale + 0.5f).toInt().coerceAtLeast(1),
                (src.height * scale + 0.5f).toInt().coerceAtLeast(1),
                true
            )
        } else null
        // scaled 是临时副本；src 是缓存里的大图，绝不能 recycle
        val work = scaled ?: src
        val w = work.width
        val h = work.height
        val pixels = IntArray(w * h)
        work.getPixels(pixels, 0, w, 0, 0, w, h)

        var semiCount = 0
        var lumaSum = 0.0
        for (p in pixels) {
            val a = (p ushr 24) and 0xFF
            // 半透明像素计数（替代「最小 alpha」：一个半透明像素不该让整张图走 alpha 通道）
            if (a < 250) semiCount++
            lumaSum += 0.299 * ((p shr 16) and 0xFF) +
                    0.587 * ((p shr 8) and 0xFF) +
                    0.114 * (p and 0xFF)
        }
        val mean = lumaSum / pixels.size / 255.0
        // 透明像素占比 >1% 才算「有透明通道」→ 以 alpha 为准；否则按亮度做软阈值
        // （否则 99.9% 不透明的图会被当成有透明，退化成实心白方块）
        val hasTransparency = semiCount * 100 > pixels.size
        // 极性自动判断：平均亮度高（浅底深图）→ 深色为实心；否则（深底浅图）→ 亮色为实心
        val darkIsSolid = mean > 0.6

        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val alpha = if (hasTransparency) {
                (p ushr 24) and 0xFF
            } else {
                val luma = 0.299 * ((p shr 16) and 0xFF) +
                        0.587 * ((p shr 8) and 0xFF) +
                        0.114 * (p and 0xFF)
                val t = if (darkIsSolid) (0.85 - luma / 255.0) else (luma / 255.0 - 0.15)
                (t / 0.5 * 255.0).coerceIn(0.0, 255.0).toInt()
            }
            out[i] = (alpha shl 24) or 0x00FFFFFF
        }
        val mask = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        scaled?.recycle()
        IconCompat.createWithBitmap(mask)
    } catch (e: Exception) {
        Log.w(TAG, "buildSilhouette failed: ${e.message}")
        null
    }
}
