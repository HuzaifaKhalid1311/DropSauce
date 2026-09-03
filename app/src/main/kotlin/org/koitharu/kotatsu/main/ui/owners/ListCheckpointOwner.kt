package org.koitharu.kotatsu.main.ui.owners

import android.view.View

/**
 * Host of the shared "jump back" pill that floats above the bottom navigation bar.
 * Screens hosted elsewhere simply have no owner and so no pill.
 */
interface ListCheckpointOwner {

	val listCheckpointButton: View?
}
