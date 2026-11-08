package app.brix.streaming.chat

import android.content.Context
import android.graphics.Bitmap
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Emote images for the chat panel.
 *
 * Glide is already a `:streaming` dependency and maintains memory and disk caches:
 * the same image in a hundred messages downloads once. For animated files,
 * `asBitmap()` returns the first frame, as required (see [EmoteParts]).
 */
object EmoteImages {
    suspend fun load(context: Context, url: String, heightPx: Int, aspect: Float): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val h = heightPx.coerceIn(16, 256)
                val w = (h * aspect).toInt().coerceIn(16, 768)
                Glide.with(context.applicationContext)
                    .asBitmap()
                    .load(url)
                    .submit(w, h)
                    .get()
            }.getOrNull()
        }
}
