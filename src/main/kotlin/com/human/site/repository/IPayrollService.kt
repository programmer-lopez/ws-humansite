package com.human.site.repository

import com.human.site.model.DownloadStatus
import com.human.site.model.PayrollFile

interface IPayrollService {
    fun startDownloadProcess(): DownloadStatus
    fun getCurrentStatus(): DownloadStatus
    fun getDownloadedFiles(): List<PayrollFile>
}
