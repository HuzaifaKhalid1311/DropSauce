package org.haziffe.dropsauce.list.ui.adapter

import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegate
import org.haziffe.dropsauce.R
import org.haziffe.dropsauce.list.ui.model.ListModel
import org.haziffe.dropsauce.list.ui.model.LoadingFooter

fun loadingFooterAD() = adapterDelegate<LoadingFooter, ListModel>(R.layout.item_loading_footer) {
}