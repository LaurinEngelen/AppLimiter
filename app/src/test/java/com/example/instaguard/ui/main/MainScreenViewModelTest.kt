package com.example.instaguard.ui.main

import com.example.instaguard.data.DataRepository
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MainScreenViewModelTest {
  @Test
  fun uiState_initiallyLoadingOrSuccess() = runTest {
    val viewModel = MainScreenViewModel(FakeMyModelRepository())
    val state = viewModel.uiState.first()
    assert(state is MainScreenUiState.Loading || state is MainScreenUiState.Success)
  }
}

private class FakeMyModelRepository : DataRepository {
  override val data: Flow<List<String>> = flow { emit(listOf("Sample")) }
}
