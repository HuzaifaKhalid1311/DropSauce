package org.koitharu.kotatsu.remotelist.ui

import android.os.Bundle
import android.widget.Toast
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuProvider
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.nav.router
import androidx.core.view.isVisible
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.ui.widgets.TipView
import org.koitharu.kotatsu.core.ui.util.MenuInvalidator
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.getCauseUrl
import org.koitharu.kotatsu.core.util.ext.isHttpUrl
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.withArgs
import org.koitharu.kotatsu.databinding.FragmentListBinding
import org.koitharu.kotatsu.extensions.install.ExtensionUpdateInstaller
import org.koitharu.kotatsu.filter.ui.FilterCoordinator
import org.koitharu.kotatsu.list.ui.MangaListFragment
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.search.domain.SearchKind
import javax.inject.Inject

@AndroidEntryPoint
class RemoteListFragment : MangaListFragment(), FilterCoordinator.Owner {

    override val viewModel by viewModels<RemoteListViewModel>()

    override val filterCoordinator: FilterCoordinator
        get() = viewModel.filterCoordinator

    @Inject
    lateinit var extensionUpdateInstallerFactory: ExtensionUpdateInstaller.Factory

    private lateinit var extensionUpdateInstaller: ExtensionUpdateInstaller
    private var updateTip: TipView? = null
    private var isInstallingUpdate = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        extensionUpdateInstaller = extensionUpdateInstallerFactory.create(this)
    }

    override fun onViewBindingCreated(binding: FragmentListBinding, savedInstanceState: Bundle?) {
        super.onViewBindingCreated(binding, savedInstanceState)
        addMenuProvider(RemoteListMenuProvider())
        addMenuProvider(MangaSearchMenuProvider(filterCoordinator, viewModel, activity))
        viewModel.isRandomLoading.observe(viewLifecycleOwner, MenuInvalidator(requireActivity()))
        viewModel.onOpenManga.observeEvent(viewLifecycleOwner) { router.openDetails(it) }
        viewModel.onBrokenSortFallback.observeEvent(viewLifecycleOwner) { showBrokenSortWarning() }
        viewModel.extensionUpdatePackage.observe(viewLifecycleOwner, ::onExtensionUpdateChanged)
        filterCoordinator.observe().distinctUntilChangedBy { it.listFilter.isEmpty() }
            .drop(1)
            .observe(viewLifecycleOwner) {
                activity?.invalidateMenu()
            }
    }

    override fun onDestroyView() {
        updateTip = null
        super.onDestroyView()
    }

    override fun onScrolledToEnd() {
        viewModel.loadNextPage()
    }

    override fun onCreateActionMode(
        controller: ListSelectionController,
        menuInflater: MenuInflater,
        menu: Menu
    ): Boolean {
        menuInflater.inflate(R.menu.mode_remote, menu)
        return super.onCreateActionMode(controller, menuInflater, menu)
    }

    override fun onFilterClick(view: View?) {
        router.showFilterSheet()
    }

    override fun onEmptyActionClick() {
        if (filterCoordinator.isFilterApplied) {
            filterCoordinator.reset()
        } else {
            openInBrowser(null) // should never be called
        }
    }

    override fun onFooterButtonClick() {
        val filter = filterCoordinator.snapshot().listFilter
        when {
            !filter.query.isNullOrEmpty() -> router.openSearch(filter.query.orEmpty(), SearchKind.SIMPLE)
            !filter.author.isNullOrEmpty() -> router.openSearch(filter.author.orEmpty(), SearchKind.AUTHOR)
            filter.tags.size == 1 -> router.openSearch(filter.tags.singleOrNull()?.title.orEmpty(), SearchKind.TAG)
        }
    }

    override fun onSecondaryErrorActionClick(error: Throwable) {
        openInBrowser(error.getCauseUrl())
    }

    private fun openInBrowser(url: String?) {
        if (url?.isHttpUrl() == true) {
            router.openBrowser(
                url = url,
                source = viewModel.source,
                title = viewModel.source.getTitle(requireContext()),
            )
        } else {
            Snackbar.make(requireViewBinding().recyclerView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT)
                .show()
        }
    }

    /**
     * Persistent, non-dismissable notice above the list while this source's extension has an update
     * waiting. The button hands the package to the extension manager, which owns the whole
     * download-and-install flow.
     */
    private fun onExtensionUpdateChanged(packageName: String?) {
        val binding = viewBinding ?: return
        if (packageName == null) {
            updateTip?.isVisible = false
            return
        }
        val tip = updateTip ?: (binding.stubTip.inflate() as TipView).also { updateTip = it }
        tip.setIcon(R.drawable.ic_extension_update)
        tip.setTitle(R.string.extension_update_available)
        tip.setText(R.string.extension_update_available_summary)
        tip.setPrimaryButtonText(R.string.update)
        tip.setClosable(false)
        tip.onButtonClickListener = object : TipView.OnButtonClickListener {
            override fun onPrimaryButtonClick(tipView: TipView) = installExtensionUpdate(packageName)

            override fun onSecondaryButtonClick(tipView: TipView) = Unit
        }
        tip.isVisible = true
    }

    /** Downloads and installs the update in place, then reloads extensions so the source picks it up. */
    private fun installExtensionUpdate(packageName: String) {
        if (isInstallingUpdate) {
            return
        }
        isInstallingUpdate = true
        Toast.makeText(requireContext(), R.string.download_started, Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatchingCancellable {
                extensionUpdateInstaller.installUpdate(packageName)
            }.onFailure {
                it.printStackTraceDebug()
            }.getOrDefault(ExtensionUpdateInstaller.Result.FAILED)
            isInstallingUpdate = false
            val message = when (result) {
                ExtensionUpdateInstaller.Result.SUCCESS -> R.string.extension_updated
                ExtensionUpdateInstaller.Result.NO_UPDATE -> R.string.no_update_available
                ExtensionUpdateInstaller.Result.DOWNLOAD_FAILED -> R.string.extension_download_failed
                ExtensionUpdateInstaller.Result.INVALID -> R.string.shizuku_invalid_package
                ExtensionUpdateInstaller.Result.FAILED -> R.string.error_occurred
            }
            Snackbar.make(viewBinding?.recyclerView ?: return@launch, message, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun showBrokenSortWarning() {
        Snackbar.make(
            viewBinding?.recyclerView ?: return,
            R.string.source_sort_broken_warning,
            Snackbar.LENGTH_LONG,
        ).show()
    }

    private inner class RemoteListMenuProvider : MenuProvider {

        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menuInflater.inflate(R.menu.opt_list_remote, menu)
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
            R.id.action_source_settings -> {
                router.openSourceSettings(viewModel.source)
                true
            }

            R.id.action_random -> {
                viewModel.openRandom()
                true
            }

            R.id.action_filter -> {
                onFilterClick(null)
                true
            }

            R.id.action_filter_reset -> {
                filterCoordinator.reset()
                true
            }

            else -> false
        }

        override fun onPrepareMenu(menu: Menu) {
            super.onPrepareMenu(menu)
            menu.findItem(R.id.action_random)?.isEnabled = !viewModel.isRandomLoading.value
            menu.findItem(R.id.action_filter_reset)?.isVisible = filterCoordinator.isFilterApplied
        }
    }

    companion object {

        const val ARG_SOURCE = "provider"

        fun newInstance(source: MangaSource) = RemoteListFragment().withArgs(1) {
            putString(ARG_SOURCE, source.name)
        }
    }
}
