package org.schabi.newpipe.local.feed.service

import androidx.annotation.StringRes
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.processors.BehaviorProcessor
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.local.feed.service.FeedEventManager.Event.IdleEvent

object FeedEventManager {
    // No global reset: the VM-local consumedTerminal replaced it (the old reset
    // self-suppressed its own event).
    private val processor: BehaviorProcessor<Event> = BehaviorProcessor.create()
    private val eventsFlowable = processor.startWithItem(IdleEvent())

    fun postEvent(event: Event) {
        processor.onNext(event)
    }

    fun events(): Flowable<Event> {
        return eventsFlowable
    }

    sealed class Event {
        abstract val groupId: Long

        data class IdleEvent(
            override val groupId: Long = FeedGroupEntity.GROUP_ALL_ID
        ) : Event()

        data class ProgressEvent(
            val currentProgress: Int = -1,
            val maxProgress: Int = -1,
            @StringRes val progressMessage: Int = 0,
            override val groupId: Long = FeedGroupEntity.GROUP_ALL_ID
        ) : Event() {
            constructor(
                @StringRes progressMessage: Int,
                groupId: Long = FeedGroupEntity.GROUP_ALL_ID
            ) : this(-1, -1, progressMessage, groupId)
        }

        data class SuccessResultEvent(
            val itemsErrors: List<Throwable> = emptyList(),
            override val groupId: Long = FeedGroupEntity.GROUP_ALL_ID,
            val cancelled: Boolean = false
        ) : Event()

        data class ErrorResultEvent(
            val error: Throwable,
            override val groupId: Long = FeedGroupEntity.GROUP_ALL_ID
        ) : Event()
    }
}
