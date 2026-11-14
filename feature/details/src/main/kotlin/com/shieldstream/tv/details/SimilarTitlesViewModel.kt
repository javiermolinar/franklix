package com.shieldstream.tv.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shieldstream.tv.domain.CatalogRepository
import com.shieldstream.tv.domain.MyListRepository
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.MediaType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SimilarTitlesUiState {
    data object Loading : SimilarTitlesUiState
    data object Error : SimilarTitlesUiState
    data class Content(
        val origin: CatalogItem,
        val items: List<CatalogItem>,
        val myListKeys: Set<String> = emptySet(),
    ) : SimilarTitlesUiState
}

@HiltViewModel
class SimilarTitlesViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val myList: MyListRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow<SimilarTitlesUiState>(SimilarTitlesUiState.Loading)
    val uiState: StateFlow<SimilarTitlesUiState> = _uiState.asStateFlow()
    private var loadedKey: String? = null
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            myList.entries.collect { entries ->
                val keys = entries.mapTo(mutableSetOf()) { it.key }
                val content = _uiState.value as? SimilarTitlesUiState.Content ?: return@collect
                _uiState.value = content.copy(myListKeys = keys)
            }
        }
    }

    fun load(type: MediaType, id: String, force: Boolean = false) {
        val key = "${type.name}_$id"
        if (!force && loadedKey == key && _uiState.value is SimilarTitlesUiState.Content) return
        loadedKey = key
        loadJob?.cancel()
        _uiState.value = SimilarTitlesUiState.Loading
        loadJob = viewModelScope.launch {
            runCatching {
                val origin = catalog.getItem(type, id) ?: error("Title not found")
                origin to catalog.getRecommendations(origin)
            }.onSuccess { (origin, items) ->
                _uiState.value = SimilarTitlesUiState.Content(
                    origin = origin,
                    items = items,
                    myListKeys = myList.entries.value.mapTo(mutableSetOf()) { it.key },
                )
            }.onFailure {
                if (it is CancellationException) throw it
                _uiState.value = SimilarTitlesUiState.Error
            }
        }
    }

    fun toggleMyList(item: CatalogItem) {
        if (myList.contains(item.type, item.id)) myList.remove(item.type, item.id) else myList.add(item.type, item.id)
    }
}
