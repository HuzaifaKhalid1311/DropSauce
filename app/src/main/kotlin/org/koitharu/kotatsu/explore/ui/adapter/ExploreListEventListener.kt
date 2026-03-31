package org.haziffe.dropsauce.explore.ui.adapter

import android.view.View
import org.haziffe.dropsauce.list.ui.adapter.ListHeaderClickListener
import org.haziffe.dropsauce.list.ui.adapter.ListStateHolderListener

interface ExploreListEventListener : ListStateHolderListener, View.OnClickListener, ListHeaderClickListener
