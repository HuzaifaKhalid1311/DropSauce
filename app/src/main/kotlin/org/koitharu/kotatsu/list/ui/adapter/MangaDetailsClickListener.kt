package org.haziffe.dropsauce.list.ui.adapter

import android.view.View
import org.haziffe.dropsauce.core.ui.list.OnListItemClickListener
import org.haziffe.dropsauce.list.ui.model.MangaListModel
import org.haziffe.dropsauce.parsers.model.Manga
import org.haziffe.dropsauce.parsers.model.MangaTag

interface MangaDetailsClickListener : OnListItemClickListener<MangaListModel> {

	fun onReadClick(manga: Manga, view: View)

	fun onTagClick(manga: Manga, tag: MangaTag, view: View)
}
