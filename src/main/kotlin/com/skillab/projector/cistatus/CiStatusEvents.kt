package com.skillab.projector.cistatus

import com.intellij.util.messages.Topic

interface CiStatusRefreshListener {
    fun refreshRequested(reason: String)

    companion object {
        @JvmField
        val TOPIC: Topic<CiStatusRefreshListener> = Topic.create("CI Status Refresh", CiStatusRefreshListener::class.java)
    }
}
