package network.columba.app.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import network.columba.app.service.TaskManager
import javax.inject.Inject

@HiltViewModel
class TaskViewModel
    @Inject
    constructor(
        val manager: TaskManager,
    ) : ViewModel()
