package org.schabi.newpipe.local.feed.service

class FeedResultsHolder {
    /**
     * List of errors that may have happen during loading.
     * Returns a snapshot: callers never observe a later clear().
     */
    val itemsErrors: List<Throwable>
        get() = itemsErrorsHolder.toList()

    private val itemsErrorsHolder: MutableList<Throwable> = ArrayList()

    fun addError(error: Throwable) {
        itemsErrorsHolder.add(error)
    }

    fun addErrors(errors: List<Throwable>) {
        itemsErrorsHolder.addAll(errors)
    }

    fun clear() {
        itemsErrorsHolder.clear()
    }
}
