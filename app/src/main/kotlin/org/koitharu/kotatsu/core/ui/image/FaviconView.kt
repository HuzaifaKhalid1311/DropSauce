package org.haziffe.dropsauce.core.ui.image

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.core.content.withStyledAttributes
import coil3.Image
import coil3.asImage
import coil3.request.Disposable
import coil3.request.ImageRequest
import org.haziffe.dropsauce.R
import org.haziffe.dropsauce.core.exceptions.resolve.CaptchaHandler.Companion.suppressCaptchaErrors
import org.haziffe.dropsauce.core.image.CoilImageView
import org.haziffe.dropsauce.core.parser.favicon.faviconUri
import org.haziffe.dropsauce.core.util.ext.isAnimationsEnabled
import org.haziffe.dropsauce.core.util.ext.mangaSourceExtra
import org.haziffe.dropsauce.core.util.ext.resolveDp
import org.haziffe.dropsauce.parsers.model.MangaSource

class FaviconView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	@AttrRes defStyleAttr: Int = 0,
) : CoilImageView(context, attrs, defStyleAttr) {

	@StyleRes
	private var iconStyle: Int = R.style.FaviconDrawable
	private val defaultBackground: Drawable? by lazy(LazyThreadSafetyMode.NONE) { background }

	init {
		context.withStyledAttributes(attrs, R.styleable.FaviconView, defStyleAttr) {
			iconStyle = getResourceId(R.styleable.FaviconView_iconStyle, iconStyle)
		}
		if (isInEditMode) {
			setImageDrawable(
				FaviconDrawable(
					context = context,
					styleResId = iconStyle,
					name = context.getString(R.string.app_name).random().toString(),
				),
			)
		}
	}

	fun setImageAsync(mangaSource: MangaSource): Disposable {
		val fallbackFactory: (ImageRequest) -> Image? = {
			FaviconDrawable(context, iconStyle, mangaSource.name).asImage()
		}
		val placeholderFactory: (ImageRequest) -> Image? = if (context.isAnimationsEnabled) {
			{ AnimatedFaviconDrawable(context, iconStyle, mangaSource.name).asImage() }
		} else {
			fallbackFactory
		}
		return enqueueRequest(
			newRequestBuilder()
				.data(mangaSource.faviconUri())
				.error(fallbackFactory)
				.fallback(fallbackFactory)
				.placeholder(placeholderFactory)
				.mangaSourceExtra(mangaSource)
				.suppressCaptchaErrors()
				.build(),
		)
	}

	/**
	 * Loads an image from [url] asynchronously. Falls back to a [FaviconDrawable] generated from
	 * [fallbackName] if the URL cannot be loaded.
	 *
	 * @param url the direct image URL to load
	 * @param fallbackName the name used to generate the fallback favicon drawable
	 * @return a [Disposable] that can be used to cancel the in-flight request
	 */
	fun setImageFromUrlAsync(url: String, fallbackName: String): Disposable {
		val fallbackFactory: (ImageRequest) -> Image? = {
			FaviconDrawable(context, iconStyle, fallbackName).asImage()
		}
		val placeholderFactory: (ImageRequest) -> Image? = if (context.isAnimationsEnabled) {
			{ AnimatedFaviconDrawable(context, iconStyle, fallbackName).asImage() }
		} else {
			fallbackFactory
		}
		return enqueueRequest(
			newRequestBuilder()
				.data(url)
				.error(fallbackFactory)
				.fallback(fallbackFactory)
				.placeholder(placeholderFactory)
				.build(),
		)
	}

	fun applyExternalSourceStyle(isExternal: Boolean) {
		if (isExternal) {
			scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
			setPadding(0, 0, 0, 0)
			strokeWidth = 0f
			background = null
		} else {
			scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
			val padding = context.resources.resolveDp(1)
			setPadding(padding, padding, padding, padding)
			strokeWidth = context.resources.resolveDp(1f).toFloat()
			background = defaultBackground
		}
	}
}
