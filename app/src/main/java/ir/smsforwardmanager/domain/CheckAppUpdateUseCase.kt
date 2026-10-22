package ir.smsforwardmanager.domain

import ir.smsforwardmanager.data.repository.AppUpdateRepository
import ir.smsforwardmanager.data.remote.model.AppVersionDto

class CheckAppUpdateUseCase(private val repository: AppUpdateRepository) {
    operator fun invoke(force: Boolean = false): AppVersionDto = repository.latest(force)
}
