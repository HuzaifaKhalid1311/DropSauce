package org.haziffe.dropsauce.reader.ui

import org.haziffe.dropsauce.reader.ui.pager.ReaderPage

data class ReaderContent(
	val pages: List<ReaderPage>,
	val state: ReaderState?
)