package com.human.site.model

enum class ProcessState {
    IDLE,
    RUNNING,
    COMPLETED,
    FAILED
}

data class DownloadStatus(
    val state: ProcessState,
    val message: String,
    val lastUpdate: String
)
