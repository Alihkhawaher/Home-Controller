package com.technlov.homecontroller.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.technlov.homecontroller.AppContainer
import com.technlov.homecontroller.app

/** A view model scoped to the current screen, built from the app's [AppContainer]. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = LocalContext.current.app
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}
