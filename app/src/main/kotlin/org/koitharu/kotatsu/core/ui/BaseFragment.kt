package org.koitharu.kotatsu.core.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding
import dagger.hilt.android.EntryPointAccessors
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.ui.util.ActionModeDelegate

abstract class BaseFragment<B : ViewBinding> :
	OnApplyWindowInsetsListener,
	Fragment() {

	var viewBinding: B? = null
		private set

	protected lateinit var exceptionResolver: ExceptionResolver
		private set

	protected val actionModeDelegate: ActionModeDelegate
		get() = (requireActivity() as BaseActivity<*>).actionModeDelegate

	private lateinit var entryPoint: BaseActivityEntryPoint

	override fun onAttach(context: Context) {
		super.onAttach(context)
		entryPoint = EntryPointAccessors.fromApplication<BaseActivityEntryPoint>(context)
		exceptionResolver = entryPoint.exceptionResolverFactory.create(this)
	}

	final override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View {
		val binding = onCreateViewBinding(inflater, container)
		viewBinding = binding
		return binding.root
	}

	final override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
			val modifiedInsets = if (entryPoint.settings.isStatusBarHidden) {
				val statusBarInsets = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())
				val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
				val newSystemBars = androidx.core.graphics.Insets.of(
					systemBarsInsets.left,
					maxOf(systemBarsInsets.top, statusBarInsets.top),
					systemBarsInsets.right,
					systemBarsInsets.bottom
				)
				WindowInsetsCompat.Builder(insets)
					.setInsets(WindowInsetsCompat.Type.systemBars(), newSystemBars)
					.build()
			} else {
				insets
			}
			onApplyWindowInsets(v, modifiedInsets)
		}
		onViewBindingCreated(requireViewBinding(), savedInstanceState)
	}

	override fun onDestroyView() {
		viewBinding = null
		super.onDestroyView()
	}

	fun requireViewBinding(): B = checkNotNull(viewBinding) {
		"Fragment $this did not return a ViewBinding from onCreateView() or this was called before onCreateView()."
	}

	protected abstract fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): B

	protected open fun onViewBindingCreated(binding: B, savedInstanceState: Bundle?) = Unit
}
