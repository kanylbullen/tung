package io.github.kanylbullen.jarn.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.kanylbullen.jarn.AppContainer
import io.github.kanylbullen.jarn.JarnApp

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as JarnApp).container

/** A factory for `viewModel(factory = ...)` without a DI framework. */
inline fun <reified VM : ViewModel> factoryOf(crossinline create: () -> VM): ViewModelProvider.Factory =
    viewModelFactory { initializer { create() } }
