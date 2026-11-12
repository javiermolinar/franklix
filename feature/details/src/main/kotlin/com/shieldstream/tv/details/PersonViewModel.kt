package com.shieldstream.tv.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shieldstream.tv.domain.CatalogRepository
import com.shieldstream.tv.domain.MyListRepository
import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.PersonDetails
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PersonUiState {
    data object Loading : PersonUiState
    data object Error : PersonUiState
    data class Content(
        val person: PersonDetails,
        val myListKeys: Set<String> = emptySet(),
    ) : PersonUiState
}

@HiltViewModel
class PersonViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val myList: MyListRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow<PersonUiState>(PersonUiState.Loading)
    val uiState: StateFlow<PersonUiState> = _uiState.asStateFlow()
    private var loadedId: Int? = null

    init {
        viewModelScope.launch {
            myList.entries.collect { entries ->
                val keys = entries.mapTo(mutableSetOf()) { it.key }
                val content = _uiState.value as? PersonUiState.Content ?: return@collect
                _uiState.value = content.copy(myListKeys = keys)
            }
        }
    }

    fun load(personId: Int, force: Boolean = false) {
        if (!force && loadedId == personId && _uiState.value is PersonUiState.Content) return
        loadedId = personId
        _uiState.value = PersonUiState.Loading
        viewModelScope.launch {
            runCatching { catalog.getPerson(personId) }
                .onSuccess {
                    _uiState.value = PersonUiState.Content(
                        person = it,
                        myListKeys = myList.entries.value.mapTo(mutableSetOf()) { entry -> entry.key },
                    )
                }
                .onFailure { _uiState.value = PersonUiState.Error }
        }
    }

    fun toggleMyList(item: CatalogItem) {
        if (myList.contains(item.type, item.id)) myList.remove(item.type, item.id) else myList.add(item.type, item.id)
    }
}
