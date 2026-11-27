package org.koitharu.kotatsu.main.ui.owners

import org.koitharu.kotatsu.core.ui.list.ListCheckpointView

/**
 * Host of the shared "jump back" pill that floats above the bottom navigation bar.
 * Screens hosted elsewhere simply have no owner and so no pill.
 */
interface ListCheckpointOwner {

	val listCheckpointButton: ListCheckpointView?
}
