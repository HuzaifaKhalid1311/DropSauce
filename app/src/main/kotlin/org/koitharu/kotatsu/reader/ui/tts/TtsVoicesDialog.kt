package org.koitharu.kotatsu.reader.ui.tts

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.tts.Voice
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.ExpressivePillButton
import org.koitharu.kotatsu.core.ui.dialog.showComposeDialog
import org.koitharu.kotatsu.core.util.ext.findActivity
import java.util.Locale

/**
 * Lets the user link any voice the phone has to each voice slot, hearing it first. Engines list
 * more voice names than they have actual voices, so only listening tells them apart.
 * [onClosed] runs when the dialog goes away, so the panel can show the new slots.
 */
fun showTtsVoicesDialog(context: Context, tts: ReaderTts, onClosed: () -> Unit) {
	showComposeDialog(context) { dismiss ->
		var slot by remember { mutableIntStateOf(tts.selectedVoiceIndex()) }
		// Bumped after every link, and on coming back from the engine's download screen.
		var revision by remember { mutableIntStateOf(0) }
		val voices = remember(revision) { tts.availableVoices() }
		val linked = remember(revision, slot) { tts.linkedVoiceName(slot) }
		val previewing by tts.previewingVoice.collectAsState()
		DisposableEffect(Unit) {
			val owner = context.findActivity() as? LifecycleOwner
			val observer = LifecycleEventObserver { _, event ->
				if (event == Lifecycle.Event.ON_RESUME) revision++
			}
			owner?.lifecycle?.addObserver(observer)
			onDispose {
				owner?.lifecycle?.removeObserver(observer)
				tts.stopPreview()
				onClosed()
			}
		}
		ExpressiveDialogCard(
			icon = painterResource(R.drawable.ic_voice_settings),
			title = stringResource(R.string.tts_customize_voices),
		) {
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				repeat(VOICE_SLOTS) { index ->
					SlotButton(
						number = index + 1,
						isSelected = index == slot,
						onClick = {
							tts.stopPreview()
							slot = index
						},
					)
				}
			}
			Spacer(Modifier.height(16.dp))
			Column(
				Modifier
					.heightIn(max = 320.dp)
					.verticalScroll(rememberScrollState()),
			) {
				VoiceRow(
					title = stringResource(R.string.system_default),
					isSelected = linked == null,
					onClick = {
						tts.linkVoice(slot, null)
						revision++
					},
					isPreviewing = false,
					onPreview = null,
				)
				voices.forEach { voice ->
					VoiceRow(
						title = voice.label(LocalContext.current),
						isSelected = voice.name == linked,
						onClick = {
							tts.linkVoice(slot, voice)
							revision++
						},
						isPreviewing = voice.name == previewing,
						onPreview = { if (voice.name == previewing) tts.stopPreview() else tts.preview(voice) },
					)
				}
			}
			Spacer(Modifier.height(16.dp))
			ExpressivePillButton(
				text = stringResource(R.string.tts_get_more_voices),
				primary = false,
				onClick = { context.openVoiceDownloads(tts.installVoicesIntent()) },
			)
			Spacer(Modifier.height(8.dp))
			ExpressivePillButton(text = stringResource(R.string.done), onClick = dismiss)
		}
	}
}

/** One of the panel's numbered voice buttons, drawn the same way: outlined, filled when picked. */
@Composable
private fun SlotButton(number: Int, isSelected: Boolean, onClick: () -> Unit) {
	val colors = MaterialTheme.colorScheme
	Surface(
		onClick = onClick,
		shape = CircleShape,
		color = if (isSelected) colors.primaryContainer else colors.surfaceContainerHigh,
		contentColor = if (isSelected) colors.onPrimaryContainer else colors.onSurfaceVariant,
		border = BorderStroke(1.dp, if (isSelected) colors.primary else colors.outlineVariant),
		modifier = Modifier.size(48.dp),
	) {
		Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
			Text(text = number.toString(), style = MaterialTheme.typography.labelLarge)
		}
	}
}

@Composable
private fun VoiceRow(
	title: String,
	isSelected: Boolean,
	isPreviewing: Boolean,
	onClick: () -> Unit,
	onPreview: (() -> Unit)?,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.heightIn(min = 52.dp)
			.clip(RoundedCornerShape(16.dp))
			.clickable(onClick = onClick)
			.padding(start = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		RadioButton(selected = isSelected, onClick = onClick)
		Spacer(Modifier.size(8.dp))
		Text(
			text = title,
			style = MaterialTheme.typography.bodyLarge,
			color = MaterialTheme.colorScheme.onSurface,
			modifier = Modifier.weight(1f),
		)
		if (onPreview != null) {
			IconButton(onClick = onPreview) {
				Icon(
					painter = painterResource(if (isPreviewing) R.drawable.ic_pause else R.drawable.ic_play),
					contentDescription = stringResource(if (isPreviewing) R.string.pause else R.string.tts_preview_voice),
				)
			}
		}
	}
}

/**
 * "US · IOL · Male": engines only give voices code names, so this is the most there is to show.
 * Gender is only known for the names that carry it.
 */
private fun Voice.label(context: Context): String = buildList {
	locale.country.takeIf { it.isNotEmpty() }?.let(::add)
	add(speakerCode()?.uppercase(Locale.ROOT) ?: name)
	when (isFemale()) {
		true -> add(context.getString(R.string.tts_voice_female))
		false -> add(context.getString(R.string.tts_voice_male))
		null -> Unit
	}
	if (isNetworkConnectionRequired) add(context.getString(R.string.tts_voice_needs_internet))
}.joinToString(" · ")

private fun Context.openVoiceDownloads(intent: Intent) {
	try {
		startActivity(intent)
	} catch (_: ActivityNotFoundException) {
		// Engines without a download screen: Android's own text-to-speech settings at least.
		try {
			startActivity(Intent("com.android.settings.TTS_SETTINGS"))
		} catch (_: ActivityNotFoundException) {
		}
	}
}
