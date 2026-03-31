package org.haziffe.dropsauce.list.ui.adapter

import org.haziffe.dropsauce.list.domain.ListFilterOption

interface QuickFilterClickListener {

	fun onFilterOptionClick(option: ListFilterOption)
}
